# NASQO Arabic Flutter release gate — 2026-11-11

## Candidate
- GitHub branch: `feature/nasqo-flutter-arabic-all-20261111`.
- Branch based on GitHub master `b11aeb3a4b70ef8970968518e5455058edcc6b0d` (reconciled for the four changed Dart files).
- Original isolated-server patch: `9b965f7`; this branch reapplies the four UI changes to the current GitHub master instead of pushing an older, divergent tree.
- CI: `.github/workflows/nasqo-flutter-arabic-release-gate.yml`.
- CI has **no deployment credentials or deployment step**.

## Changed UI
1. Splash screen: loading menu status now uses `CoreLocalizations.loadingMenuConfiguration`.
2. Balance summary: previous-year tooltip uses `OrderAccountingLocalizations.previousYear`.
3. Revenue/expense chart: next-year tooltip uses `OrderAccountingLocalizations.nextYear`.
4. Add another invoice item: title, field labels and validation use existing accounting ARB getters.

## Mandatory release gates
1. GitHub Actions job must complete successfully: localization key check, Flutter bootstrap/generation, analyzer, package tests and Admin Web release build.
2. Verify actual production Web source compatibility. The running Web image is `nasqo/web:quill-i18n-candidate-20261111`, with separate `accounting-review.js`, `session-resume.js`, and release branding/HTML. A plain `flutter build web` artifact **must not** replace the production image without incorporating and verifying these overlays.
3. Keep previous exact image digest and Compose config for rollback; no backend/database changes for translation.
4. Verify public landing/login and authorized test UI in Arabic and English; do not access customer records.
5. Deploy only after gates pass, using a candidate image built from the exact verified source and preserving the existing production Web behavior.

## Status
- Static checks passed locally on server.
- CI dispatched automatically by push to the branch; see GitHub Actions.
- Production **not deployed**.
- Server has approximately 2.2 GiB free and no local Flutter SDK; build belongs on GitHub-hosted runners.
