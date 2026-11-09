#!/usr/bin/env groovy
// Run with compiled ADK classes and their deployed dependency classpath.
// Exercises the real manager with fake auth facades; no network, secrets or database.
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.UserFacade
import java.util.concurrent.atomic.AtomicInteger

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
Map toolsets = field('configMcpToolsets').get(null) as Map
def saved = [key: key.get(null), retry: retry.get(null), service: service.get(null),
             shutdown: shutdown.get(null), toolsets: new LinkedHashMap(toolsets)]
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
] as UserFacade
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
    println 'ADK_OPTIONAL_MCP_AUTH_REGRESSION_PASS'
} finally {
    key.set(null, saved.key)
    retry.set(null, saved.retry)
    service.set(null, saved.service)
    shutdown.set(null, saved.shutdown)
    toolsets.clear(); toolsets.putAll(saved.toolsets)
}
