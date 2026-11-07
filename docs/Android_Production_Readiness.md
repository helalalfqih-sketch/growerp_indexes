# Independent Android production release — readiness gate

This is NOT an approved release. The Flutter Admin source is derived from
GrowERP (Apache-2.0). Verify license/notice obligations and use your own brand
and store listing; do not assume rights to the original GrowERP trademarks.

The current master snapshot (540d3ac9320fe97012520e7f9624c75591f5a4ee)
has package ID org.growerp.admin, display name GrowERP Administrator, upstream
app links, and backend URLs at backend.growerp.com. It also has the version
1.22.4+141; this is not proof of a signed or tested commercial release.

## Repeatable fail-closed check

Run from the repository root:

    python3 ci/android_production_preflight.py --strict --check-signing --min-version-code 141

The default minimum build code is only the number in the preview discussed
during assessment. Supply the ACTUAL highest published version code before
releasing, and use a greater one. The script does not print signing secrets or
make network requests; it does not upload releases.

The pull-request CI job intentionally uses audit/report mode without keystore
secrets; a green job means the *checker* works, not that production blockers
have disappeared. Strict mode rejects unresolved blockers.

## Hard release gates — obtain evidence for every gate

1. Register your own permanent app ID, branding, privacy policy and Android
   app-link domains before publishing. Changing the package ID after customers
   install creates a separate app rather than an in-place update.
2. Deploy an approved HTTPS/WSS Moqui backend under your control, with access
   controls, tenant isolation, monitoring, backups and restoration. Verify
   login, customer creation, invoice posting, ledger correctness, taxes,
   inventory updates and exports against isolated real server test data.
3. Keep keystore and key.properties out of Git. Securely provide release signing
   credentials to the build runner, then build an AAB for store distribution
   and inspect its package ID, version code, signing certificate and integrity.
4. Remove unnecessary Android permissions after auditing actual features;
   review sensitive data use, data deletion, retention and Play Console's
   current policy and target-SDK requirements before store submission.
5. Test Arabic RTL, mixed numeric values, PDF receipt shaping, and printing on
   supported Android devices. Test degraded network and long-lived sessions.
6. For Shopify: the indexesstore.com connector PR #7 is a READ-ONLY
   synchronization preview. Do not claim two-way write synchronization until
   authentication, mapping, webhooks, idempotency, conflicts, rate limits,
   retries and reversible write operations are implemented and validated.
   Test with an isolated Shopify store or approved test fixtures first.
7. Perform a documented acceptance test with at least two distinct Android
   device sizes, including account isolation, backups and recovery, and keep
   a rollback procedure. Run static checks AND all relevant Flutter/backend
   tests at the exact release candidate commit.

A green CI badge or a downloadable APK alone does not satisfy these gates.
No automatic deployment, publishing, store mutations or Play track changes are
performed by this change.
