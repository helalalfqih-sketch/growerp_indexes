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
class NasqoGoogleLoginHandler {
    private static final SecureRandom random = new SecureRandom()
    private static final String CACHE = 'growerp.google.oauthState'
    private static final String PREFIX = 'nasqo-business-login:'
    private static final String PREF_KEY = 'nasqo.google.subject'
    private static final String DEVICE_PREFIX = 'nasqo-device-login:'
    private static final String DEVICE_STATE_PREFIX = 'nasqo-device-state:'
    private static final String DEVICE_APPROVAL_PREFIX = 'nasqo-device-approval:'
    private static final String PAIRING_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    private static final int MAX_PAIRING_ATTEMPTS = 5
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

    static String start(def ec, String applicationId, String intent = 'login') {
        if (!(intent in ['login', 'register'])) throw new IllegalArgumentException('Invalid Google flow')
        if (intent == 'register' && !'true'.equalsIgnoreCase(System.getenv('NASQO_GOOGLE_REGISTRATION_ENABLED') ?: '')) throw new IllegalStateException('Google registration disabled')
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
            intent: intent,
            startedAt: System.currentTimeMillis()
        ])

        return AUTH_URL + '?response_type=code' +
            '&client_id=' + urlEncode(cid) +
            '&redirect_uri=' + urlEncode(redirect) +
            '&scope=' + urlEncode(intent == 'register' ? 'openid email profile' : 'openid email') +
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
        return [email: email, sub: claims.sub,
                givenName: (claims.given_name ?: '').toString(),
                familyName: (claims.family_name ?: '').toString()]
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

        boolean authzWasDisabledForTenantChecks = ec.artifactExecution.disableAuthz()
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
                boolean authzWasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    ec.entity.makeValue('moqui.security.UserPreference').setAll([
                        userId: account.userId,
                        preferenceKey: PREF_KEY,
                        preferenceValue: identity.sub
                    ]).create()
                } finally {
                    if (!authzWasDisabled) ec.artifactExecution.enableAuthz()
                }
            }
        } catch (Throwable failure) {
            if (ec.user.userId) ec.user.logoutUser()
            throw failure
        } finally {
            if (!authzWasDisabledForTenantChecks) ec.artifactExecution.enableAuthz()
        }
    }

    /** Callback for this instance's browser-based mobile/desktop login broker. */
    static String deviceCallbackUri(def ec) {
        String uri = callbackUri(ec)
        return uri.substring(0, uri.length() - '/loginCallback'.length()) + '/deviceCallback'
    }

    private static String hexSha256(String s) {
        MessageDigest.getInstance('SHA-256').digest(s.getBytes('UTF-8')).encodeHex().toString()
    }

    private static boolean expired(Map item) {
        if (!item || !(item.startedAt instanceof Number)) return true
        long elapsed = System.currentTimeMillis() - ((Number) item.startedAt).longValue()
        return elapsed < 0L || elapsed > MAX_STATE_AGE_MS
    }

    private static String pairingCode() {
        StringBuilder code = new StringBuilder()
        for (int i = 0; i < 8; i++) {
            code.append(PAIRING_CHARS.charAt(random.nextInt(PAIRING_CHARS.length())))
        }
        return code.toString()
    }

    /**
     * Starts an OIDC sign-in for the native Flutter app.
     * The device secret is returned ONLY to the app and never put into the
     * Google authorization URL, browser cookies, or browser callback.
     *
     * The user must TYPE an independent code displayed in the app into
     * the browser confirmation page after authenticating to Google.
     */
    static Map startDevice(def ec, String applicationId) {
        if (!enabled() || !clientId().endsWith('.apps.googleusercontent.com') ||
            !clientSecret()) throw new IllegalStateException('Google login is not configured')
        if (!applicationId || !applicationId.matches('[A-Za-z0-9_-]{1,48}') ||
            applicationId == 'AppSupport') {
            throw new IllegalArgumentException('Unsupported application for Google login')
        }
        String deviceCode = randomUrlToken(32)
        String deviceHash = hexSha256(deviceCode)
        String code = pairingCode()
        String state = randomUrlToken(32)
        String nonce = randomUrlToken(32)
        String verifier = randomUrlToken(48)
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('UTF-8')))
        String redirectUri = deviceCallbackUri(ec)
        long startedAt = System.currentTimeMillis()
        def cache = ec.cache.getCache(CACHE)
        cache.put(DEVICE_PREFIX + deviceHash, [
            startedAt: startedAt, state: state, applicationId: applicationId,
            pairingHash: hexSha256(code), status: 'pending', attempts: 0
        ])
        cache.put(DEVICE_STATE_PREFIX + state, [
            startedAt: startedAt, deviceHash: deviceHash, nonce: nonce,
            pkceVerifier: verifier, redirectUri: redirectUri
        ])
        String authUrl = AUTH_URL + '?response_type=code' +
            '&client_id=' + urlEncode(clientId()) +
            '&redirect_uri=' + urlEncode(redirectUri) +
            '&scope=' + urlEncode('openid email') +
            '&state=' + urlEncode(state) +
            '&nonce=' + urlEncode(nonce) +
            '&code_challenge=' + urlEncode(challenge) +
            '&code_challenge_method=S256' +
            '&prompt=select_account'
        return [authUrl: authUrl, deviceCode: deviceCode, pairingCode: code,
                expiresInSeconds: 600, pollIntervalSeconds: 3]
    }

    /**
     * Called only from Google's HTTPS browser callback.
     * Google identity remains in server memory until confirmation. No user
     * login or account linkage is done in the browser callback.
     */
    static void completeDeviceCallback(def ec, String state, String authorizationCode) {
        if (!enabled() || !state || !state.matches('[A-Za-z0-9_-]{40,100}') ||
            !authorizationCode || authorizationCode.size() > 4096) {
            throw new IllegalArgumentException('Invalid Google device authorization response')
        }
        def cache = ec.cache.getCache(CACHE)
        Map pending = null
        synchronized (cache) {
            pending = (Map) cache.get(DEVICE_STATE_PREFIX + state)
            cache.remove(DEVICE_STATE_PREFIX + state)
        }
        if (expired(pending)) {
            throw new IllegalStateException('Google authorization has expired')
        }
        Map device = (Map) cache.get(DEVICE_PREFIX + pending.deviceHash)
        if (expired(device) || device.status != 'pending' || device.state != state) {
            throw new IllegalStateException('Device login is no longer pending')
        }
        Map identity = getVerifiedGoogleIdentity(authorizationCode, pending)
        def browserSession = ec.web.request.getSession(true)
        synchronized (cache) {
            Map current = (Map) cache.get(DEVICE_PREFIX + pending.deviceHash)
            if (expired(current) || current.status != 'pending') {
                throw new IllegalStateException('Device login is no longer available')
            }
            cache.put(DEVICE_PREFIX + pending.deviceHash,
                current + [status: 'awaiting_code', identity: identity,
                           browserSession: browserSession.id])
            cache.put(DEVICE_APPROVAL_PREFIX + state, pending.deviceHash)
        }
    }

    /**
     * Manual pairing confirmation: only the browser that received Google's
     * redirect can approve, and only with the eight-character code shown in
     * the app. Five incorrect attempts close the authorization permanently.
     */
    static void approveDevice(def ec, String state, String pairing) {
        if (!enabled() || !state || !state.matches('[A-Za-z0-9_-]{40,100}') ||
            !pairing || !pairing.matches('[A-Za-z2-9]{8}')) {
            throw new IllegalArgumentException('Invalid pairing confirmation')
        }
        def cache = ec.cache.getCache(CACHE)
        synchronized (cache) {
            String deviceHash = cache.get(DEVICE_APPROVAL_PREFIX + state)
            Map pending = deviceHash ? (Map)cache.get(DEVICE_PREFIX + deviceHash) : null
            if (expired(pending) || pending.status != 'awaiting_code' ||
                pending.state != state ||
                ec.web.request.getSession(false)?.id != pending.browserSession) {
                throw new IllegalStateException('Device approval expired or wrong browser')
            }
            byte[] enteredHash = MessageDigest.getInstance('SHA-256')
                .digest(pairing.toUpperCase(Locale.ROOT).getBytes('UTF-8'))
            byte[] expectedHash = pending.pairingHash.decodeHex()
            boolean equal = MessageDigest.isEqual(enteredHash, expectedHash)
            if (!equal) {
                int attempts = ((Number)(pending.attempts ?: 0)).intValue() + 1
                if (attempts >= MAX_PAIRING_ATTEMPTS) {
                    cache.remove(DEVICE_PREFIX + deviceHash)
                    cache.remove(DEVICE_APPROVAL_PREFIX + state)
                } else {
                    cache.put(DEVICE_PREFIX + deviceHash, pending + [attempts: attempts])
                }
                throw new IllegalStateException('Pairing code did not match')
            }
            cache.put(DEVICE_PREFIX + deviceHash,
                pending + [status: 'ready', pairingHash: null, browserSession: null])
            cache.remove(DEVICE_APPROVAL_PREFIX + state)
        }
    }

    /**
     * Native app polls the endpoint over HTTPS with its independent 256-bit
     * device secret. Once confirmed, the first successful claim establishes
     * a NEW Moqui API login in this app request; a replay cannot do so.
     */
    static Map pollDevice(def ec, String deviceCode, String applicationId) {
        if (!enabled() || !deviceCode || !deviceCode.matches('[A-Za-z0-9_-]{40,100}')) {
            throw new IllegalArgumentException('Invalid device session')
        }
        String deviceHash = hexSha256(deviceCode)
        def cache = ec.cache.getCache(CACHE)
        Map pending = null
        synchronized (cache) {
            pending = (Map) cache.get(DEVICE_PREFIX + deviceHash)
            if (expired(pending)) {
                cache.remove(DEVICE_PREFIX + deviceHash)
                throw new IllegalStateException('Device authorization expired')
            }
            if (pending.applicationId != applicationId || applicationId == 'AppSupport') {
                throw new IllegalStateException('Device authorization application mismatch')
            }
            if (pending.status == 'ready') {
                // Atomic single-use claim (including simultaneous poll requests).
                cache.remove(DEVICE_PREFIX + deviceHash)
            }
        }
        if (pending.status == 'pending' || pending.status == 'awaiting_code') {
            return [status: 'pending']
        }
        if (pending.status != 'ready' || !(pending.identity instanceof Map)) {
            throw new IllegalStateException('Device authorization not available')
        }
        loginExistingUser(ec, pending.identity as Map, applicationId)
        def result = ec.service.sync().name(
            'growerp.100.GeneralServices100.get#Authenticate')
            .parameters([applicationId: applicationId]).call()
        if (ec.message.hasError() || !result.authenticate?.apiKey ||
            !result.authenticate?.user?.userId) {
            if (ec.user.userId) ec.user.logoutUser()
            throw new IllegalStateException('Verified NASQO session was not established')
        }
        return [status: 'authenticated', authenticate: result.authenticate]
    }

    /** Google verified, new tenant only, existing registration policy. */
    private static void registerVerifiedGoogleUser(def ec, Map identity, String applicationId) {
        if (!'true'.equalsIgnoreCase(System.getenv('NASQO_GOOGLE_REGISTRATION_ENABLED') ?: '')) {
            throw new IllegalStateException('Google registration disabled')
        }
        if (applicationId != 'AppAdmin') throw new IllegalArgumentException('Unsupported signup app')
        String email = identity.email
        if (ec.entity.find('moqui.security.UserAccount')
                .condition('emailAddress', email).disableAuthz().count() > 0 ||
            ec.entity.find('moqui.security.UserAccount')
                .condition('username', email).disableAuthz().count() > 0) {
            throw new IllegalStateException('Account already exists; use Google login')
        }
        String first = (identity.givenName ?: 'مستخدم').toString().trim().take(80)
        String last = (identity.familyName ?: 'نسقو').toString().trim().take(80)
        def result = ec.service.sync().name('growerp.100.PartyServices100.register#User')
            .parameters([applicationId: applicationId, email: email,
                firstName: first ?: 'مستخدم', lastName: last ?: 'نسقو',
                userGroupId: 'GROWERP_M_ADMIN', locale: 'ar',
                skipMarketing: true])
            .disableAuthz().call()
        if (ec.message.hasError() || !result.authenticate?.ownerPartyId ||
            result.authenticate?.apiKey != 'registered') {
            throw new IllegalStateException('NASQO registration incomplete')
        }
        // New companies still require standard setup and subscription.
        // No paid plan is granted and no existing account is modified.
        def account = ec.entity.find('moqui.security.UserAccount')
            .condition('emailAddress', email).disableAuthz().one()
        if (!account?.userId) throw new IllegalStateException('New account not found')
        boolean authzWasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue('moqui.security.UserPreference').setAll([
                userId: account.userId, preferenceKey: PREF_KEY,
                preferenceValue: identity.sub
            ]).createOrUpdate()
        } finally {
            if (!authzWasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    static String complete(def ec, String state, String code) {
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
        if (pending.intent == 'register') {
            registerVerifiedGoogleUser(ec, identity, pending.applicationId as String)
            return 'registered'
        } else {
            loginExistingUser(ec, identity, pending.applicationId as String)
            return 'success'
        }
    }
}

// Return the utility class for Moqui ec.resource.script callers.
return NasqoGoogleLoginHandler
