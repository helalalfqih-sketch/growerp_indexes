/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * NASQO Business user sign-in via Google's OpenID Connect authorization-code flow.
 *
 * NOT Google Workspace authorization: requests only "openid email", does not
 * request Drive/Gmail/Calendar scopes, and never stores an access/refresh token.
 * Only EXISTING, enabled users can sign in. No account, role, or tenant creation.
 *
 * A confidential Web OAuth client is required; no client secret goes to Flutter.
 * The state is one-time, bound to the initiating browser session, and expires.
 * PKCE and nonce protect the code exchange. ID tokens are verified by Google's
 * tokeninfo service over TLS (including signature) and all claims are checked.
 */
class NasqoGoogleLoginUtil {
    private static final SecureRandom random = new SecureRandom()
    private static final String CACHE = 'growerp.google.oauthState'
    private static final String PREFIX = 'nasqo-business-login:'
    private static final String PREF_KEY = 'nasqo.google.subject'
    private static final long MAX_STATE_AGE_MS = 10L * 60L * 1000L
    private static final String AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth'
    private static final String TOKEN_URL = 'https://oauth2.googleapis.com/token'
    private static final String VERIFY_URL = 'https://oauth2.googleapis.com/tokeninfo'

    static boolean enabled() {
        'true'.equalsIgnoreCase(System.getenv('NASQO_GOOGLE_LOGIN_ENABLED') ?: '')
    }

    static String clientId() {
        return System.getenv('GOOGLE_LOGIN_CLIENT_ID') ?: ''
    }

    private static String clientSecret() {
        return System.getenv('GOOGLE_LOGIN_CLIENT_SECRET') ?: ''
    }

    private static String randomUrlToken(int bytes) {
        byte[] data = new byte[bytes]
        random.nextBytes(data)
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private static String urlEncode(Object value) {
        URLEncoder.encode(value.toString(), 'UTF-8')
    }

    static String callbackUri(def ec) {
        String explicitUri = System.getenv('GOOGLE_LOGIN_REDIRECT_URI') ?: ''
        String uri = explicitUri ?: ec.web.getWebappRootUrl(true, null)
            .replaceAll('/+$', '') + '/googleOAuth/loginCallback'
        // Do not send an authorization code to an untrusted or plaintext redirect.
        if (!uri.startsWith('https://') &&
            !('true'.equalsIgnoreCase(System.getenv('NASQO_GOOGLE_LOGIN_ALLOW_LOCAL_HTTP') ?: '')
              && uri.startsWith('http://localhost:'))) {
            throw new IllegalStateException('Google login requires a secure HTTPS callback')
        }
        if (!uri.endsWith('/googleOAuth/loginCallback')) {
            throw new IllegalStateException('Unexpected Google login callback path')
        }
        return uri
    }

    static String start(def ec, String applicationId) {
        if (!enabled()) throw new IllegalStateException('Google login is not enabled')
        String cid = clientId()
        if (!cid.endsWith('.apps.googleusercontent.com') || !clientSecret()) {
            throw new IllegalStateException('Google login OAuth client is not configured')
        }
        if (!applicationId || !applicationId.matches('[A-Za-z0-9_-]{1,48}') ||
            applicationId == 'AppSupport') {
            throw new IllegalArgumentException('Unsupported application for Google login')
        }

        String redirect = callbackUri(ec)
        String state = randomUrlToken(32)
        String nonce = randomUrlToken(32)
        String verifier = randomUrlToken(48)
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('UTF-8')))
        def httpSession = ec.web.request.getSession(true)
        ec.cache.getCache(CACHE).put(PREFIX + state, [
            browserSession: httpSession.id,
            redirectUri: redirect,
            nonce: nonce,
            pkceVerifier: verifier,
            applicationId: applicationId,
            startedAt: System.currentTimeMillis()
        ])

        return AUTH_URL + '?response_type=code' +
            '&client_id=' + urlEncode(cid) +
            '&redirect_uri=' + urlEncode(redirect) +
            '&scope=' + urlEncode('openid email') +
            '&state=' + urlEncode(state) +
            '&nonce=' + urlEncode(nonce) +
            '&code_challenge=' + urlEncode(challenge) +
            '&code_challenge_method=S256' +
            '&prompt=select_account'
    }

    private static Map readJson(HttpsURLConnection conn) {
        try {
            if (conn.responseCode != 200) {
                throw new IllegalStateException('Google authentication service rejected the request')
            }
            def parsed = new JsonSlurper().parseText(conn.inputStream.getText('UTF-8'))
            if (!(parsed instanceof Map)) throw new IllegalStateException('Invalid Google response')
            return parsed as Map
        } finally {
            conn.disconnect()
        }
    }

    private static Map getVerifiedGoogleIdentity(String code, Map pending) {
        HttpsURLConnection tokenConn = (HttpsURLConnection) new URL(TOKEN_URL).openConnection()
        tokenConn.requestMethod = 'POST'
        tokenConn.connectTimeout = 10000
        tokenConn.readTimeout = 10000
        tokenConn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
        tokenConn.doOutput = true
        String body = 'grant_type=authorization_code' +
            '&code=' + urlEncode(code) +
            '&client_id=' + urlEncode(clientId()) +
            '&client_secret=' + urlEncode(clientSecret()) +
            '&redirect_uri=' + urlEncode(pending.redirectUri) +
            '&code_verifier=' + urlEncode(pending.pkceVerifier)
        tokenConn.outputStream.withCloseable { os ->
            os.write(body.getBytes('UTF-8'))
        }
        Map tokens = readJson(tokenConn)
        String idToken = tokens.id_token ?: ''
        if (!idToken || idToken.size() > 16000) {
            throw new IllegalStateException('Google returned no valid identity token')
        }

        HttpsURLConnection verifyConn = (HttpsURLConnection) new URL(
            VERIFY_URL + '?id_token=' + urlEncode(idToken)).openConnection()
        verifyConn.connectTimeout = 10000
        verifyConn.readTimeout = 10000
        Map claims = readJson(verifyConn)
        // tokeninfo is a Google-hosted HTTPS verifier. No unsigned JWT payload is trusted.
        return validateIdentityClaims(claims, clientId(), pending.nonce as String)
    }

    /** Pure policy check; useful for regression tests without Google credentials. */
    static Map validateIdentityClaims(Map claims, String expectedClientId, String expectedNonce) {
        // The trusted Google tokeninfo endpoint has validated the JWT signature.
        // We must additionally validate the claims and their intended client.
        long now = System.currentTimeMillis().intdiv(1000L)
        long expires = (claims.exp ?: '0').toString().toLong()
        long issued = (claims.iat ?: '0').toString().toLong()
        if (claims.aud != expectedClientId ||
            (claims.azp && claims.azp != expectedClientId) ||
            !(claims.iss in ['accounts.google.com', 'https://accounts.google.com']) ||
            expires <= now || issued > now + 60L ||
            issued < now - 3600L ||
            claims.nonce != expectedNonce ||
            !(claims.sub instanceof String) || !claims.sub ||
            !('true'.equalsIgnoreCase(claims.email_verified?.toString())) ||
            !(claims.email instanceof String)) {
            throw new IllegalStateException('Google identity token failed verification')
        }

        String email = claims.email.trim().toLowerCase(Locale.ROOT)
        if (!email.matches('[^@\\s]+@[^@\\s]+\\.[^@\\s]+')) {
            throw new IllegalStateException('Invalid verified Google email')
        }
        String emailDomain = email.substring(email.lastIndexOf('@') + 1)
        // For third-party email addresses, "email_verified" may describe an old
        // verification. Only Gmail or currently Google-hosted Workspace domains.
        if (!(emailDomain in ['gmail.com', 'googlemail.com']) &&
            !emailDomain.equalsIgnoreCase(claims.hd?.toString() ?: '')) {
            throw new IllegalStateException('Google login requires Gmail or Google Workspace')
        }
        return [email: email, sub: claims.sub]
    }

    private static void loginExistingUser(def ec, Map identity, String applicationId) {
        String email = identity.email
        def users = ec.entity.find('moqui.security.UserAccount')
            .condition('username', email).disableAuthz().list()
        if (users.isEmpty()) {
            users = ec.entity.find('moqui.security.UserAccount')
                .condition('emailAddress', email).disableAuthz().list()
        }
        if (users.size() != 1) {
            throw new IllegalStateException('No unique registered account for Google email')
        }
        def account = users[0]
        if (account.disabled == 'Y' || account.requirePasswordChange == 'Y' ||
            !account.userId || !account.partyId) {
            throw new IllegalStateException('Account must be enabled with no pending password reset')
        }
        // Google login must not become a bypass for system support or MFA.
        def sysGroups = ec.entity.find('moqui.security.UserGroupMember')
            .condition('userId', account.userId)
            .condition('userGroupId', 'GROWERP_M_SYSTEM')
            .disableAuthz().list()
        if (!sysGroups.isEmpty()) {
            throw new IllegalStateException('System accounts require normal login')
        }
        def factors = ec.entity.find('moqui.security.UserAuthcFactor')
            .condition('userId', account.userId).disableAuthz().list()
        if (!factors.isEmpty()) {
            throw new IllegalStateException('Use normal login for an MFA-protected account')
        }

        def party = ec.entity.find('mantle.party.Party')
            .condition('partyId', account.partyId).disableAuthz().one()
        if (!party?.ownerPartyId || party.disabled == 'Y') {
            throw new IllegalStateException('Account has no active tenant')
        }
        def linked = ec.entity.find('moqui.security.UserPreference')
            .condition('userId', account.userId)
            .condition('preferenceKey', PREF_KEY).disableAuthz().one()
        if (linked && linked.preferenceValue != identity.sub) {
            throw new IllegalStateException('This account is linked to a different Google identity')
        }
        // Never trust the requested tenant or role; look both up from the
        // existing account in Moqui before issuing a session.
        if (ec.user.userId && ec.user.userId != account.userId) {
            ec.user.logoutUser()
        }
        ec.user.internalLoginUser(account.username)
        if (ec.user.userId != account.userId) {
            throw new IllegalStateException('Moqui did not establish the expected user session')
        }

        try {
            def setup = ec.service.sync().name(
                'growerp.100.TenantServices100.check#TenantSetupStatus')
                .parameters([ownerPartyId: party.ownerPartyId]).call()
            if (setup.setupComplete != true || ec.message.hasError()) {
                throw new IllegalStateException('Finish company setup through normal login first')
            }
            if (party.ownerPartyId != 'GROWERP') {
                def subscription = ec.service.sync().name(
                    'growerp.100.TenantServices100.check#TenantSubscription')
                    .parameters([ownerPartyId: party.ownerPartyId, email: email]).call()
                if (subscription.hasActiveSubscription != true || ec.message.hasError()) {
                    throw new IllegalStateException('Subscription requires normal login')
                }
            }
            if (!linked) {
                ec.entity.makeValue('moqui.security.UserPreference').setAll([
                    userId: account.userId,
                    preferenceKey: PREF_KEY,
                    preferenceValue: identity.sub
                ]).create()
            }
        } catch (Throwable failure) {
            if (ec.user.userId) ec.user.logoutUser()
            throw failure
        }
    }

    static void complete(def ec, String state, String code) {
        if (!enabled()) throw new IllegalStateException('Google login not enabled')
        if (!state || !state.matches('[A-Za-z0-9_-]{40,100}') ||
            !code || code.size() > 4096) {
            throw new IllegalArgumentException('Invalid Google authorization response')
        }
        def cache = ec.cache.getCache(CACHE)
        Map pending = (Map) cache.get(PREFIX + state)
        cache.remove(PREFIX + state) // One-time: do not allow replay.
        def browserSession = ec.web.request.getSession(false)
        if (!pending || browserSession == null ||
            pending.browserSession != browserSession.id ||
            System.currentTimeMillis() - ((Number) pending.startedAt).longValue() > MAX_STATE_AGE_MS ||
            System.currentTimeMillis() < ((Number) pending.startedAt).longValue()) {
            throw new IllegalStateException('Google login expired or started in another browser')
        }
        Map identity = getVerifiedGoogleIdentity(code, pending)
        loginExistingUser(ec, identity, pending.applicationId as String)
    }
}
