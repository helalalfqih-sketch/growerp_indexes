/*
 * Copyright NASQO contributors. Licensed under the Apache License, Version 2.0.
 *
 * Credential-free Google Business login identity policy checks.
 * Standalone: java -cp '/path/to/gradle/lib/*' groovy.ui.GroovyMain \
 *            backend/src/test/groovy/NasqoGoogleLoginPolicyUnit.groovy
 */
import groovy.lang.GroovyClassLoader

File code = new File('backend/service/NasqoGoogleLoginUtil.groovy')
assert code.isFile(): 'Run this test at the repository root'
Class util = new GroovyClassLoader(this.class.classLoader).parseClass(code)
String client = '12345-nasqo.apps.googleusercontent.com'
String nonce = 'test-nonce-will-never-be-used-in-production'
long now = System.currentTimeMillis().intdiv(1000L)
Map valid = [
    iss: 'https://accounts.google.com', aud: client,
    sub: 'google-subject-123', nonce: nonce,
    exp: (now + 1800L).toString(), iat: (now - 20L).toString(),
    email: 'Sample.User@Gmail.com', email_verified: 'true'
]
assert util.validateIdentityClaims(valid, client, nonce) ==
    [email: 'sample.user@gmail.com', sub: 'google-subject-123']

int negativeCases = 0
def mustDeny = { Map change, String label ->
    try {
        util.validateIdentityClaims(valid + change, client, nonce)
    } catch (IllegalStateException expected) {
        negativeCases++
        return
    }
    throw new AssertionError("Expected denial for: " + label)
}
mustDeny([aud: 'another-client.apps.googleusercontent.com'], 'wrong audience')
mustDeny([azp: 'another-client.apps.googleusercontent.com'], 'wrong authorized party')
mustDeny([iss: 'https://not-google.example'], 'fake issuer')
mustDeny([nonce: 'different'], 'wrong nonce')
mustDeny([exp: (now - 1L).toString()], 'expired token')
mustDeny([iat: (now + 200L).toString()], 'future issued-at')
mustDeny([iat: (now - 7200L).toString()], 'stale issued-at')
mustDeny([sub: ''], 'missing stable Google identity')
mustDeny([email_verified: 'false'], 'unverified email')
mustDeny([email: 'invalid-email'], 'invalid email')
mustDeny([email: 'contact@thirdparty.example'], 'unhosted third party email')
mustDeny([email: 'person@company.example', hd: 'wrong-company.example'], 'mismatched hosted domain')
assert util.validateIdentityClaims(
    valid + [email: 'person@company.example', hd: 'company.example'], client, nonce
) == [email: 'person@company.example', sub: 'google-subject-123']
assert util.validateIdentityClaims(
    valid + [iss: 'accounts.google.com'], client, nonce
).email == 'sample.user@gmail.com'

println "NASQO Google Business sign-in policy: PASS (2 valid, ${negativeCases} denials)"
