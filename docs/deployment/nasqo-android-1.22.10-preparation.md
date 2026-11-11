# NASQO Android 1.22.10 (build 147) — private release preparation

## Scope / source of truth
- Android app: `flutter/packages/admin` built from the production source branch
  `release/nasqo-production-foundation`, base commit
  `782fde06e51d2094e736729b2350567188e2a128`.
- UI functionality includes merged PR #38 (Arabic customers/CSV/invoice party)
  and #39 (account balance search/sort/fiscal periods/empty states).
- Permanent Android package ID: `com.indexesstore.nasqo`.
- Native launch activity: `org.growerp.admin.MainActivity`.
- Android versionName: `1.22.10`, versionCode `147`.
  When building with `--split-per-abi`, Flutter encodes ABI offsets:
  ARM64 APK versionCode is **2147** (previous 2146) and x86_64 is
  **4147** (previous 4146). This is higher than published 1.22.9 and
  supports in-place upgrades when signed with the same permanent key.
- Production API: `https://erp.indexesstore.com`;
  WebSocket: `wss://erp.indexesstore.com`.
- Arabic app label / NASQO scheme are restored from the previously reviewed
  master Android release configuration. Do **not** switch back to
  `org.growerp.admin` or `backend.growerp.com`.

## Build and verification
- GitHub Actions: `.github/workflows/nasqo-android-1-22-10.yml`
- Flutter 3.47.9 / Java 17; build release APKs for `arm64-v8a`
  (supported phones) and `x86_64` (Android emulator only).
- CI is configured `NASQO_BUILD_UNSIGNED=true`: only unsigned APKs are
  uploaded as **private review artifacts**; no permanent signing key leaves
  the private VPS.
- Verify ZIP integrity, package ID, version, native ABI, alignment, no
  debuggable flag, embedded app endpoints, and smaller size than the prior
  198.6 MB debug preview.
- Sign a separate **disposable emulator-only copy** in CI using a temporary
  test keystore, verify its signature, then test Android 13 install, launch,
  process survival for 30 seconds, uninstall/reinstall.
- CI test-signed copies are never distributed. Do not try to install an
  unsigned GitHub artifact on a customer's phone.

## Production signing — not publication
- After CI succeeds, transfer the unsigned artifacts privately to the VPS
  release directory; verify artifact ZIP checksums and all APK metadata.
- Permanent signing material stays in
  `/home/chatgpt-admin/nasqo-cutover/private/android-signing/`.
- Sign with the existing private NASQO keystore, then verify `apksigner`
  v2/v3 signatures, `zipalign` 16 KB, package/version, exact SHA256,
  and the same public certificate SHA256 as 1.22.9. Never log passwords.
- Output only into a **private** release directory; do **not** write to
  web `downloads/`, update an `index.html` link, create a GitHub
  release, or send an install link without a separate publication decision.

## Acceptance boundaries
- Emulator tests validate x86_64 launch but cannot prove installation on an
  actual ARM64 Samsung phone, login, or the full business acceptance tests.
- This is a packaging/versioning/signing change only; it does not authorize
  database or financial changes, backend deployment, or automatic APK rollout.
- See `docs/deployment/nasqo-android-release.md` on master for the prior
  1.22.9 historical verification and signing procedure, without copying its
  hardcoded version/run IDs into the new release.
