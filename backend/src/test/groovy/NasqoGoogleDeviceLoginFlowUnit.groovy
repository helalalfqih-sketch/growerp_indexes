/*
 * NASQO Google native device pairing policy regression tests.
 * No OAuth credentials, network, database or user accounts are accessed.
 */
import groovy.lang.GroovyClassLoader
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

class MockSession { String id = 'google-browser' }
class MockRequest {
    MockSession session = new MockSession()
    MockSession getSession(boolean create) { session }
}
class MockWeb {
    MockRequest request = new MockRequest()
    String getWebappRootUrl(boolean secure, Object target) {
        'https://erp.indexesstore.com'
    }
}
class MockCache {
    Map entries = new ConcurrentHashMap()
    Object get(String key) { entries.get(key) }
    Object put(String key, Object value) { entries.put(key,value) }
    Object remove(String key) { entries.remove(key) }
}
class MockCacheFacade {
    MockCache cache = new MockCache()
    MockCache getCache(String name) {
        assert name == 'growerp.google.oauthState'
        cache
    }
}
class MockEc {
    MockWeb web = new MockWeb()
    MockCacheFacade cache = new MockCacheFacade()
}
def assertThrows = { Closure thunk, Class exceptionType ->
    try { thunk.call() }
    catch (Throwable failure) {
        assert exceptionType.isAssignableFrom(failure.class):
            'Expected ' + exceptionType.simpleName + ', saw ' + failure.class.simpleName
        return
    }
    throw new AssertionError('Expected ' + exceptionType.simpleName)
}
Class util = new GroovyShell(this.class.classLoader).evaluate(
    new File('backend/service/NasqoGoogleLoginUtil.groovy')
)
MockEc ec = new MockEc()
Map started = util.startDevice(ec, 'AppAdmin')
assert started.authUrl.startsWith('https://accounts.google.com/o/oauth2/v2/auth?')
assert started.authUrl.contains('scope=openid+email')
assert started.authUrl.contains('code_challenge_method=S256')
assert !started.authUrl.contains(started.deviceCode)
assert !started.authUrl.contains(started.pairingCode)
assert !started.authUrl.contains('gmail') && !started.authUrl.contains('drive')
assert started.deviceCode.matches('[A-Za-z0-9_-]{40,100}')
assert started.pairingCode.matches('[A-Z2-9]{8}')
assert started.pollIntervalSeconds == 3
assert started.expiresInSeconds == 600

def q = started.authUrl.split('\\?',2)[1].split('&')
    .collectEntries { item ->
        def pair = item.split('=',2)
        [(pair[0]): URLDecoder.decode(pair[1], 'UTF-8')]
    }
assert q.redirect_uri == 'https://erp.indexesstore.com/googleOAuth/deviceCallback'
assert ec.cache.cache.entries.size() == 2

assertThrows({ util.pollDevice(ec, 'too-short', 'AppAdmin') }, IllegalArgumentException)
assert util.pollDevice(ec, started.deviceCode, 'AppAdmin').status == 'pending'
assertThrows({ util.pollDevice(ec, started.deviceCode, 'AppSupport') }, IllegalStateException)
assertThrows({ util.pollDevice(ec, started.deviceCode, 'WrongApp') }, IllegalStateException)
assertThrows({ util.startDevice(ec, 'AppSupport') }, IllegalArgumentException)

// Simulate only the POST-Google-identity-verified transition; the real
// callback can enter this state only after tokeninfo validated Google.
String deviceKey = ec.cache.cache.entries.keySet().find { it.startsWith('nasqo-device-login:') }
String stateKey = ec.cache.cache.entries.keySet().find { it.startsWith('nasqo-device-state:') }
String pendingState = stateKey.substring('nasqo-device-state:'.length())
def original = ec.cache.cache.get(deviceKey)
def afterGoogle = original + [
    status: 'awaiting_code',
    identity: [email: 'test@gmail.com',sub: 'google-123'],
    browserSession: ec.web.request.session.id
]
ec.cache.cache.put(deviceKey, afterGoogle)
ec.cache.cache.put('nasqo-device-approval:' + pendingState,
    deviceKey.substring('nasqo-device-login:'.length()))

// A different browser, correct pairing code: still denied.
ec.web.request.session.id = 'unrelated-browser'
assertThrows({
    util.approveDevice(ec,pendingState,started.pairingCode)
}, IllegalStateException)
ec.web.request.session.id = 'google-browser'

// Wrong code increments attempt count but never approves.
assertThrows({
    util.approveDevice(ec,pendingState,'AAAAAAAA')
}, IllegalStateException)
assert ec.cache.cache.get(deviceKey).attempts == 1
assert ec.cache.cache.get(deviceKey).status == 'awaiting_code'

// Right code approves only once, and no Google secret was exposed.
util.approveDevice(ec,pendingState,started.pairingCode)
assert ec.cache.cache.get(deviceKey).status == 'ready'
assert ec.cache.cache.get(deviceKey).pairingHash == null
assertThrows({
    util.approveDevice(ec,pendingState,started.pairingCode)
}, IllegalStateException)

// Without actual Moqui data, final login will fail but device secret
// must still be consumed atomically (not reusable).
assertThrows({
    util.pollDevice(ec,started.deviceCode,'AppAdmin')
}, Throwable)
assert ec.cache.cache.get(deviceKey) == null
assertThrows({
    util.pollDevice(ec,started.deviceCode,'AppAdmin')
}, IllegalStateException)
println 'NASQO Google native device pairing: PASS (challenge, browser binding, code, single-use)'
