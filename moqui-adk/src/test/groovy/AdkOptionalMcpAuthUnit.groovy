#!/usr/bin/env groovy
// Run with compiled ADK classes and their deployed dependency classpath.
// Exercises the real manager with fake auth facades; no network, secrets or database.
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.UserFacade
import java.util.concurrent.atomic.AtomicInteger

// internalLoginUser is implementation-only in Moqui 4.0; expose it on the test
// proxy as well so the fake executes the same dynamic call as UserFacadeImpl.
interface InternalLoginUserFacade extends UserFacade {
    boolean internalLoginUser(String username)
}

Class manager = Class.forName('org.moqui.adk.AdkManager')
def field = { String name ->
    def f = manager.getDeclaredField(name)
    f.accessible = true
    f
}
def method = { String name, Class... types ->
    def m = manager.getDeclaredMethod(name, types)
    m.accessible = true
    m
}
def key = field('mcpApiKey')
def retry = field('mcpKeyRetryAfterMs')
def service = field('sharedSessionService')
def shutdown = field('shuttingDown')
def artifacts = field('sharedArtifactService')
def currentConfig = field('currentConfig')
Map registry = field('registry').get(null) as Map
Map agents = field('agentRegistry').get(null) as Map
Map tenants = field('tenantRegistry').get(null) as Map
String priorProviderProperty = System.getProperty('GOOGLE_API_KEY')
Map toolsets = field('configMcpToolsets').get(null) as Map
def saved = [key: key.get(null), retry: retry.get(null), service: service.get(null),
             shutdown: shutdown.get(null), toolsets: new LinkedHashMap(toolsets),
             artifacts: artifacts.get(null), currentConfig: currentConfig.get(null),
             registry: new LinkedHashMap(registry), agents: new LinkedHashMap(agents),
             tenants: new LinkedHashMap(tenants)]
AtomicInteger loginCalls = new AtomicInteger()
AtomicInteger keyCalls = new AtomicInteger()
AtomicInteger destroyed = new AtomicInteger()
boolean loginAllowed = false
String loggedInUser = null
String loggedInName = null
boolean throwLogin = false
def user = [
    internalLoginUser: { String name ->
        assert name == 'SystemSupport'
        loginCalls.incrementAndGet()
        if (throwLogin) throw new IllegalStateException('disabled account')
        loginAllowed
    },
    getUserId: { loggedInUser },
    getUsername: { loggedInName },
    getLoginKey: { float hours ->
        keyCalls.incrementAndGet()
        assert loginAllowed && loggedInUser && loggedInName == 'SystemSupport'
        'test-only-internal-key'
    }
] as InternalLoginUserFacade
def ec = [getUser: { user }, destroy: { destroyed.incrementAndGet(); null }] as ExecutionContext
def ecf = [getExecutionContext: { ec }] as ExecutionContextFactory
def resolve = method('resolveMcpApiKey', ExecutionContextFactory)
try {
    key.set(null, null)
    retry.set(null, 0L)
    shutdown.set(null, false)

    // Authentication returning false must never mint a login key.
    assert resolve.invoke(null, ecf) == null
    assert loginCalls.get() == 1
    assert keyCalls.get() == 0
    assert destroyed.get() == 1
    assert (retry.get(null) as long) > System.currentTimeMillis()
    assert resolve.invoke(null, ecf) == null
    assert loginCalls.get() == 1: 'Repeated runner initialization ignored the cooldown'

    // A throwing disabled-account login also fails closed and cleans up its own EC.
    retry.set(null, 0L)
    throwLogin = true
    assert resolve.invoke(null, ecf) == null
    assert loginCalls.get() == 2
    assert keyCalls.get() == 0
    assert destroyed.get() == 2

    // A true return without a bound identity cannot mint a key either.
    retry.set(null, 0L)
    throwLogin = false
    loginAllowed = true
    assert resolve.invoke(null, ecf) == null
    assert keyCalls.get() == 0

    // Successful dedicated-account auth remains usable; the credential is cached.
    retry.set(null, 0L)
    loggedInUser = 'SYSTEM_SUPPORT'
    loggedInName = 'SystemSupport'
    assert resolve.invoke(null, ecf) == 'test-only-internal-key'
    assert keyCalls.get() == 1
    int authenticatedAttempts = loginCalls.get()
    assert resolve.invoke(null, ecf) == 'test-only-internal-key'
    assert loginCalls.get() == authenticatedAttempts

    // No authenticated service key means no lazy transport is attached (thus no 401 retry).
    key.set(null, null)
    retry.set(null, System.currentTimeMillis() + 300_000L)
    service.set(null, null)
    int originalToolsets = toolsets.size()
    assert method('buildMcpToolset', String, String, String, Boolean.TYPE)
            .invoke(null, 'test-internal-agent', 'test-owner', 'readOnly', false) == null
    assert toolsets.size() == originalToolsets

    // An HTTP session never inherits a shared service identity, even if one is valid.
    key.set(null, 'test-only-admin-key')
    assert method('buildMcpToolset', String, String, String, Boolean.TYPE)
            .invoke(null, 'HTTP_INTERACTIVE_test-owner', 'test-owner', 'readOnly', false) == null
    assert toolsets.size() == originalToolsets

    String prompt = method('contextPreambleForMcp', Boolean.TYPE).invoke(null, false)
    assert prompt.contains('cannot query live ERP records')
    assert prompt.contains('Never invent data')
    assert prompt.contains('{screenCatalog}')
    assert prompt.contains('{tenantId}')
    assert !prompt.contains('moqui_execute_service')
    assert !prompt.contains('searchKnowledge')
    assert method('contextPreambleForMcp', Boolean.TYPE).invoke(null, true)
            .contains('moqui_search_services')
    assert method('lookupToolMode', String).invoke(null, 'HTTP_INTERACTIVE_test-owner') == 'readOnly'

    // Legacy email/GitHub/Substack helpers trust model-supplied owner IDs: not HTTP tools.
    List httpTools = method('assembleHttpFunctionTools').invoke(null) as List
    assert httpTools.contains(com.google.adk.tools.LoadArtifactsTool.INSTANCE)
    assert httpTools.findAll { !it.is(com.google.adk.tools.LoadArtifactsTool.INSTANCE) }
            .collect { it.name() } == ['getCurrentTime']
    // Exercise complete HTTP runner construction, including the real Google ADK builder.
    // The fake key constructs a client only; no model generation request is made.
    key.set(null, null)
    service.set(null, null)
    artifacts.set(null, null)
    String httpConfigId = 'HTTP_INTERACTIVE_test-owner'
    registry.remove(httpConfigId); agents.remove(httpConfigId); tenants.remove('test-owner')
    manager.initConfig(httpConfigId, 'test-owner', null, 'gemini-2.5-flash', '',
            'test-only-not-a-provider-credential', 'gemini', null)
    assert registry[httpConfigId] instanceof com.google.adk.runner.Runner:
            'Optional MCP failure prevented HTTP runner registration'
    assert tenants['test-owner'] == httpConfigId
    def agent = agents[httpConfigId]
    assert agent != null
    assert agent.description().contains('internal ERP tools unavailable')
    assert !agent.description().contains('access to Moqui MCP tools')
    // Inspect the union before resolving it: the test must not make any network calls.
    assert agent.toolsUnion().every { it instanceof com.google.adk.tools.BaseTool }
    assert agent.toolsUnion().size() == 2
    assert agent.toolsUnion().contains(com.google.adk.tools.LoadArtifactsTool.INSTANCE)
    assert agent.toolsUnion().findAll { !it.is(com.google.adk.tools.LoadArtifactsTool.INSTANCE) }
            .collect { it.name() } == ['getCurrentTime']
    assert agent.canonicalTools().toList().blockingGet().size() == 2
    String assembledPrompt = agent.canonicalInstruction(null).blockingGet().key
    assert assembledPrompt.contains('cannot query live ERP records')
    assert assembledPrompt.contains('{screenCatalog}')
    assert !assembledPrompt.contains('moqui_execute_service')
    assert !assembledPrompt.contains('searchKnowledge')
    assert !assembledPrompt.contains('readEmails')
    assert !assembledPrompt.contains('getFileContent')
    assert toolsets.size() == originalToolsets
    println 'ADK_OPTIONAL_MCP_AUTH_REGRESSION_PASS'
} finally {
    key.set(null, saved.key)
    retry.set(null, saved.retry)
    service.set(null, saved.service)
    shutdown.set(null, saved.shutdown)
    artifacts.set(null, saved.artifacts)
    currentConfig.set(null, saved.currentConfig)
    registry.clear(); registry.putAll(saved.registry)
    agents.clear(); agents.putAll(saved.agents)
    tenants.clear(); tenants.putAll(saved.tenants)
    if (priorProviderProperty == null) System.clearProperty('GOOGLE_API_KEY')
    else System.setProperty('GOOGLE_API_KEY', priorProviderProperty)
    toolsets.clear(); toolsets.putAll(saved.toolsets)
}
