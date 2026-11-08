/*
 * Copyright NASQO contributors. Apache-2.0.
 * No real Google keys, sessions or external network access are used.
 */
import groovy.lang.GroovyClassLoader

class TestSession {
    String id
}
class TestRequest {
    TestSession session = new TestSession(id: 'codespace-browser-session')
    TestSession getSession(boolean create) { session }
}
class TestWeb {
    TestRequest request = new TestRequest()
    String getWebappRootUrl(boolean useHttps, Object ignored) {
        'https://erp.indexesstore.com'
    }
}
class TestCache {
    Map<String,Object> entries = [:]
    Object get(String key) { entries[key] }
    Object put(String key, Object v) { entries.put(key, v) }
    Object remove(String key) { entries.remove(key) }
}
class TestCacheFacade {
    TestCache cache = new TestCache()
    TestCache getCache(String key) {
        assert key == 'growerp.google.oauthState'
        cache
    }
}
class TestEc {
    TestWeb web = new TestWeb()
    TestCacheFacade cache = new TestCacheFacade()
}

Class util = new GroovyShell(this.class.classLoader).evaluate(
    new File('backend/service/NasqoGoogleLoginUtil.groovy')
)
assert util.enabled()
TestEc ec = new TestEc()
String uri = util.callbackUri(ec)
assert uri == 'https://erp.indexesstore.com/googleOAuth/loginCallback'
String authUrl = util.start(ec, 'AppAdmin')
assert authUrl.startsWith('https://accounts.google.com/o/oauth2/v2/auth?')
assert authUrl.contains('code_challenge_method=S256')
assert authUrl.contains('scope=openid+email')
assert !authUrl.contains('test-confidential-secret')
assert !authUrl.contains('drive.readonly')
assert !authUrl.contains('mail.google.com')
assert ec.cache.cache.entries.size() == 1

String query = new java.net.URI(authUrl).rawQuery
Map params = query.split('&').collectEntries { kv ->
    def pair = kv.split('=', 2)
    [(pair[0]): java.net.URLDecoder.decode(pair[1], 'UTF-8')]
}
assert params.redirect_uri == uri
assert params.client_id == System.getenv('GOOGLE_LOGIN_CLIENT_ID')
assert params.state.size() >= 40
assert params.nonce.size() >= 40
assert params.code_challenge.size() >= 40

boolean denied = false
try { util.start(ec, 'AppSupport') } catch (IllegalArgumentException expected) { denied = true }
assert denied

// Browser session mismatch must stop BEFORE any Google token exchange.
ec.web.request.session.id = 'another-browser'
denied = false
try { util.complete(ec, params.state, 'fake-code') }
catch (IllegalStateException expected) { denied = true }
assert denied
assert ec.cache.cache.entries.isEmpty() // one-time even on failure

println 'NASQO OAuth URL, state binding, PKCE and anti-replay: PASS'
