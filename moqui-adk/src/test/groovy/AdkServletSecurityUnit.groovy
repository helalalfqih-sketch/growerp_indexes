#!/usr/bin/env groovy
/*
 * Standalone regression for the real AdkDevServlet source.
 * Classpath: Groovy (+ json), Jakarta servlet API, SLF4J API only.
 * Exclude Moqui/component jars: collaborators below never call a network or LLM.
 * groovy AdkServletSecurityUnit.groovy path/to/AdkDevServlet.groovy
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ReadListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

assert args.length == 1 : 'Pass the actual AdkDevServlet.groovy source path'
def loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass('''
package org.moqui.context
class ExecutionContextFactory {
    def executionContext
    def getExecutionContext() { executionContext }
    void destroyActiveExecutionContext() { executionContext?.destroy() }
}
''')
loader.parseClass('''
package org.moqui.resource
interface ResourceReference { InputStream openStream(); boolean getExists() }
''')
def manager = loader.parseClass('''
package org.moqui.adk
class AdkManager {
    static final String APP_NAME = 'moqui-adk'
    static List calls = []
    static List sessions = []
    static void lazyInit(Object factory) { calls << [op:'init'] }
    static List listAgents() { calls << [op:'listAgents']; ['test-agent'] }
    static Map createSession(String user, Map state, boolean requireTenant = false) {
        calls << [op:'create', user:user, state:new LinkedHashMap(state), requireTenant:requireTenant]
        [id:'new-test-session', appName:APP_NAME, userId:user, state:state]
    }
    static List listSessions(String user) {
        calls << [op:'list', user:user]
        sessions.findAll { it.userId == user }
    }
    static Map getSession(String user, String sid, String owner = null) {
        calls << [op:'get', user:user, id:sid, owner:owner]
        sessions.find { it.userId == user && it.id == sid }
    }
    static void deleteSession(String user, String sid, String owner = null) {
        calls << [op:'delete', user:user, id:sid, owner:owner]
    }
    static List runAgent(String user, String sid, String text, String owner = null) {
        calls << [op:'run', user:user, id:sid, owner:owner]
        []
    }
    static void runAgentSse(String user, String sid, String text,
                           Closure event, Closure done, String owner = null, boolean block = false) {
        calls << [op:'run', user:user, id:sid, owner:owner, block:block]
        event([id:'test-stream-event'])
        done(null)
    }
}
''')
def servletClass = loader.parseClass(new File(args[0]))
def service = servletClass.getDeclaredMethod(
    'service', HttpServletRequest, HttpServletResponse)
service.accessible = true

class FakeUser {
    String userId
    String username
    Set groups = []
    TimeZone timeZone = TimeZone.getTimeZone('UTC')
    Locale locale = Locale.ENGLISH
    void initFromHttpRequest(Object request, Object response) {}
    boolean isInGroup(String group) { groups.contains(group) }
    boolean hasPermission(String group) { groups.contains(group) }
}
class FakeArtifact {
    boolean disabled = false
    boolean disableAuthz() { boolean old = disabled; disabled = true; old }
    void enableAuthz() { disabled = false }
}
class FakeRow extends LinkedHashMap {
    List table
    FakeRow(Map values, List rows) { super(values); table = rows }
    void delete() { table.remove(this) }
    void update() {}
}
class FakeFind {
    List table
    Map filters = [:]
    FakeFind condition(String key, Object value) { filters[key] = value; this }
    FakeFind selectField(String ignored) { this }
    FakeFind selectFields(Object ignored) { this }
    FakeFind disableAuthz() { this }
    FakeFind useCache(boolean ignored) { this }
    FakeFind limit(int ignored) { this }
    FakeFind orderBy(Object ignored) { this }
    def one() { list().find() }
    List list() { table.findAll { row -> filters.every { k, v -> row[k] == v } } }
}
class FakeEntity {
    Map tables = [:]
    def find(String name) { new FakeFind(table: tables[name] ?: []) }
    void table(String name, List<Map> values) {
        def rows = []
        values.each { rows << new FakeRow(it, rows) }
        tables[name] = rows
    }
}
class FakeEC {
    FakeUser user
    FakeEntity entity
    FakeArtifact artifactExecution = new FakeArtifact()
    def message = [hasError: { false }]
    int destroyCount = 0
    def webImpl
    def getWebImpl() { webImpl }
    void initWebFacade(String app, Object req, Object resp) {}
    void destroy() { destroyCount++ }
}
class BytesInput extends ServletInputStream {
    private final ByteArrayInputStream bytes
    BytesInput(String text) { bytes = new ByteArrayInputStream(text.getBytes('UTF-8')) }
    int read() { bytes.read() }
    boolean isFinished() { bytes.available() == 0 }
    boolean isReady() { true }
    void setReadListener(ReadListener listener) {}
}

def invoke = { Map spec ->
    manager.calls.clear()
    manager.sessions = [
        [id:'mine', userId:'alice'],
        [id:'other-user', userId:'bob'],
        [id:'other-tenant', userId:'alice'],
    ]
    def entity = new FakeEntity()
    entity.table('moqui.security.UserAccount', [
        [userId:'alice', username:'alice', partyId:'party-alice', userFullName:'Alice'],
        [userId:'bob', username:'bob', partyId:'party-bob', userFullName:'Bob'],
    ])
    entity.table('mantle.party.Party', [
        [partyId:'party-alice', ownerPartyId:'tenant-a'],
        [partyId:'party-bob', ownerPartyId:'tenant-b'],
    ])
    entity.table('moqui.adk.AdkSession', [
        [adkSessionId:'mine', appName:'moqui-adk', userId:'alice',
         stateJson:JsonOutput.toJson([tenantId:'tenant-a']), configId:'HTTP_INTERACTIVE_tenant-a'],
        [adkSessionId:'other-user', appName:'moqui-adk', userId:'bob',
         stateJson:JsonOutput.toJson([tenantId:'tenant-b']), configId:'HTTP_INTERACTIVE_tenant-b'],
        [adkSessionId:'other-tenant', appName:'moqui-adk', userId:'alice',
         stateJson:JsonOutput.toJson([tenantId:'tenant-b']), configId:'HTTP_INTERACTIVE_tenant-b'],
        [adkSessionId:'foreign-runner', appName:'moqui-adk', userId:'alice',
         stateJson:JsonOutput.toJson([tenantId:'tenant-a']), configId:'HTTP_INTERACTIVE_tenant-b'],
        [adkSessionId:'legacy-runner', appName:'moqui-adk', userId:'alice',
         stateJson:JsonOutput.toJson([tenantId:'tenant-a']), configId:'INTERACTIVE_tenant-a'],
    ])
    entity.table('moqui.adk.AdkAgentConfig', [
        [adkAgentConfigId:'config-a', ownerPartyId:'tenant-a', agentName:'a'],
        [adkAgentConfigId:'config-b', ownerPartyId:'tenant-b', agentName:'b'],
    ])
    def userId = spec.containsKey('user') ? spec.user : 'alice'
    def ec = new FakeEC(
        user:new FakeUser(userId:userId, username:userId,
                          groups:(spec.groups ?: ['AdkUsers']) as Set),
        entity:entity)
    if (spec.groups != null) ec.user.groups = spec.groups as Set
    def factory = loader.loadClass('org.moqui.context.ExecutionContextFactory')
        .newInstance()
    factory.executionContext = ec
    Map attributes = [:]
    def context = [
        getAttribute: { String key ->
            key == 'executionContextFactory' ? factory : null
        },
        getInitParameter: { String key -> key == 'moqui-name' ? 'webroot' : null },
    ] as ServletContext
    String payload = spec.body == null ? '' : JsonOutput.toJson(spec.body)
    def input = new BytesInput(payload)
    boolean readerUsed = false
    boolean streamUsed = false
    def request = [
        getPathInfo: { spec.path },
        getRequestURI: { '/adk' + spec.path },
        getMethod: { spec.method ?: 'GET' },
        getHeader: { String key -> spec.headers?.get(key) },
        getServerName: { 'erp.example.invalid' },
        getScheme: { 'https' },
        getServletContext: { context },
        getAttribute: { String key -> attributes[key] },
        setAttribute: { String key, Object value -> attributes[key] = value },
        removeAttribute: { String key -> attributes.remove(key) },
        getReader: {
            assert !streamUsed : 'Servlet mixed input stream and reader'
            readerUsed = true
            new BufferedReader(new InputStreamReader(input, 'UTF-8'))
        },
        getInputStream: {
            assert !readerUsed : 'Servlet mixed reader and input stream'
            streamUsed = true
            input
        },
    ] as HttpServletRequest
    int status = 200
    def output = new StringWriter()
    def writer = new PrintWriter(output)
    def response = [
        setStatus: { int value -> status = value },
        getStatus: { status },
        sendError: { Object... values -> status = values[0] as int },
        setHeader: { String key, String value -> },
        addHeader: { String key, String value -> },
        setContentType: { String value -> },
        getWriter: { writer },
        isCommitted: { false },
    ] as HttpServletResponse
    def servlet = servletClass.newInstance()
    try {
        service.invoke(servlet, request, response)
    } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.targetException
    }
    writer.flush()
    [status:status, body:output.toString(), calls:new ArrayList(manager.calls),
     entity:entity, ec:ec]
}

def rejected = { Map spec, int expected ->
    def result = invoke(spec)
    assert result.status == expected :
        'Expected ' + expected + ' for ' + spec + ', got ' + result
    assert !result.calls.any { it.op in ['create','get','list','delete','run'] } :
        'Rejected caller reached session/agent execution'
    result
}

// Authentication/group authorization must precede agent initialization.
for (String path in ['/list-apps', '/configs',
                     '/apps/moqui-adk/users/alice/sessions', '/run', '/run_sse']) {
    def result = rejected([path:path, user:null,
        method:path.startsWith('/run') ? 'POST' : 'GET'], 401)
    assert result.calls.empty : 'Anonymous request triggered ADK initialization'
}
rejected([path:'/list-apps', groups:[]], 403)
rejected([path:'/list-apps', user:'unknown'], 403)

// Requested identity cannot override authenticated identity.
rejected([path:'/apps/moqui-adk/users/bob/sessions', method:'POST', body:[:]], 403)
for (String path in ['/run', '/run_sse']) {
    rejected([path:path, method:'POST',
        body:[appName:'moqui-adk', userId:'bob', sessionId:'mine', newMessage:[parts:[[text:'test']]]]], 403)
}

// No cross-user/cross-tenant read, deletion, or agent execution.
for (String sid in ['other-user','other-tenant','foreign-runner','legacy-runner']) {
    for (String verb in ['GET','DELETE']) {
        rejected([path:'/apps/moqui-adk/users/alice/sessions/' + sid, method:verb], 404)
    }
    for (String path in ['/run','/run_sse']) {
        rejected([path:path, method:'POST',
            body:[appName:'moqui-adk', userId:'alice', sessionId:sid, newMessage:[parts:[[text:'test']]]]], 404)
    }
}

// Only harmless client UI state may augment server-derived identity.
def created = invoke([path:'/apps/moqui-adk/users/alice/sessions', method:'POST',
    body:[state:[tenantId:'tenant-b', userId:'bob', username:'bob',
                 currentUserId:'bob', organizationName:'Other',
                 screenCatalog:'safe-screen-catalog']]])
assert created.status == 200 : created
def createCall = created.calls.find { it.op == 'create' }
assert createCall.user == 'alice'
assert createCall.requireTenant
assert createCall.state.tenantId == 'tenant-a'
assert createCall.state.userId == 'alice'
assert createCall.state.username == 'alice'
assert createCall.state.currentUserId in [null, 'alice']
assert createCall.state.organizationName != 'Other'
assert createCall.state.screenCatalog == 'safe-screen-catalog'

// Allowed callers retain the existing protocol.
for (String verb in ['GET','DELETE']) {
    def own = invoke([path:'/apps/moqui-adk/users/alice/sessions/mine', method:verb])
    assert own.status == 200 : own
    assert own.calls.any { it.op == (verb == 'GET' ? 'get' : 'delete') &&
        it.user == 'alice' && it.id == 'mine' && it.owner == 'tenant-a' }
}
def run = invoke([path:'/run', method:'POST',
    body:[appName:'moqui-adk', userId:'alice', sessionId:'mine', newMessage:[parts:[[text:'test']]]]])
assert run.status == 200 && run.calls.any {
    it.op == 'run' && it.id == 'mine' && it.owner == 'tenant-a'
}
def stream = invoke([path:'/run_sse', method:'POST',
    body:[appName:'moqui-adk', userId:'alice', sessionId:'mine',
          newMessage:[parts:[[text:'test']]]]])
assert stream.status == 200 && stream.calls.any {
    it.op == 'run' && it.owner == 'tenant-a' && it.block == true
}
assert stream.body.contains('data: [DONE]') && stream.body.contains('test-stream-event')

def cfg = invoke([path:'/configs'])
assert cfg.status == 200 : cfg
def configs = new JsonSlurper().parseText(cfg.body)
assert configs*.adkAgentConfigId == ['config-a']
rejected([path:'/configs/config-b', method:'DELETE'], 403)
rejected([path:'/configs/config-b', method:'DELETE', groups:['ADMIN']], 404)
rejected([path:'/configs', method:'POST', groups:['ADMIN'],
    body:[ownerPartyId:'tenant-b']], 403)
def listing = invoke([path:'/apps/moqui-adk/users/alice/sessions'])
assert new JsonSlurper().parseText(listing.body)*.id == ['mine']
def admin = invoke([path:'/list-apps', groups:['ADMIN']])
assert admin.status == 200
def preflight = invoke([path:'/run_sse', method:'OPTIONS', user:null])
assert preflight.status == 204 && preflight.calls.empty

rejected([path:'/list-apps', headers:['Origin':'https://evil.invalid',
    'Host':'erp.example.invalid']], 403)
def sameOrigin = invoke([path:'/list-apps', headers:[
    'Origin':'https://erp.example.invalid','Host':'erp.example.invalid']])
assert sameOrigin.status == 200
assert invoke([path:'/list-apps', groups:['GROWERP_M_ADMIN']]).status == 200
println 'ADK_SERVLET_SECURITY_REGRESSION_PASS'
