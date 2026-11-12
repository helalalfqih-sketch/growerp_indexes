# AI_HANDOFF — NASQO cloud development (2026-11-12)

## Scope and ownership
- Repo: `helalalfqih-sketch/growerp_indexes`; development branch: `chore/nasqo-codespaces-20261112`; base: `master`.
- Goal: use GitHub Codespaces for Flutter 3.47.9, Moqui Java 21, Docker-in-Docker, Android SDK tools, with **dot** as a lightweight authorized remote terminal.
- `nasqo-integration` is an Apify Python template, not the primary ERP source; it was not modified.
- Production Compose, databases, signing keys and existing workflows were not modified.

## Files changed
- `.devcontainer/Dockerfile`: Ubuntu 24.04 dev base, pinned official Flutter and Android SDK CLI archives with SHA-256 verification; Linux Flutter build prerequisites.
- `.devcontainer/devcontainer.json`: Java 21, Docker-in-Docker, Node 22, private forwarded dev port 8080, VS Code extensions.
- `.devcontainer/post-create.sh`: non-production toolchain check and Melos activation.
- `.devcontainer/bootstrap.sh`: optional `check`, `flutter`, `backend`, and `android` actions. Never auto-accept Android SDK licenses.
- `docs/agents/nasqo-codespaces.md`: setup, verification, testing, costing, dot-to-Codespaces SSH instructions.

## Inspected evidence
- `flutter/pubspec.yaml`: Dart ^3.10 and Melos ^7.8.1; many workspace packages.
- `flutter/packages/admin/android/gradle/wrapper/gradle-wrapper.properties`: Gradle 9.1.0.
- `flutter/packages/admin/android/settings.gradle.kts`: Android Gradle Plugin 9.0.1, KGP 2.3.20.
- `deploy/nasqo/compose.yaml`: production PostgreSQL 17, Flutter 3.47.9, Moqui Java 21.
- `docs/agents/dev-setup.md`, `CLAUDE.md`: existing backend submodule/bootstrap patterns and tests.
- `nasqo-android-release.yml`: existing CI APK release validation (kept unchanged).
- Flutter checksum from official releases_linux.json; Android CLI checksum from developer.android.com.

## Tests performed (on dot via Remote Desktop Commander)
- PASS: downloaded new branch files from GitHub.
- PASS: `python3 -m json.tool .devcontainer/devcontainer.json`.
- PASS: `bash -n .devcontainer/post-create.sh .devcontainer/bootstrap.sh`.
- PASS: no-argument bootstrap exits with usage rather than performing an operation.
- PASS: configuration assertions (pinned versions, feature names, checksums, no production Compose calls).
- PASS: official Flutter and Android archive URLs respond HTTP 200 to HEAD.
- NOT RUN: devcontainer image build, Docker daemon, Gradle build, Flutter analysis/tests, Android APK build. dot has no local Docker Engine.
- NOT RUN: live Codespaces build; **existing** Codespace `didactic-disco-4j9q544wpjxp27q75` is stopped with uncommitted changes and one unpushed commit on `release/nasqo-production-foundation`. Do not switch or rebuild it.

## Current risks / blockers
1. New Codespace based on this branch is required for end-to-end tests; Codespaces may incur compute/storage charges.
2. Existing Codespace has uncommitted/unpushed work; preserve it.
3. Android SDK terms require the user's interactive acceptance. `sdkmanager --licenses` is NOT automated.
4. Full Moqui/Flutter setup may require additional system resources and downloads; benchmark before using as daily development host.
5. dot's GitHub CLI login now works, but SSH into an active NASQO Codespace has not been verified.
6. No secrets or credentials were recorded in this handoff.

## Next steps
1. Review the draft pull request; do not merge before checking a clean Codespace image build.
2. With an appropriate Codespaces machine/quota, create a **separate** Codespace from `chore/nasqo-codespaces-20261112`.
3. Run `bash .devcontainer/bootstrap.sh check`, then Flutter + backend setup and tests per `docs/agents/nasqo-codespaces.md`.
4. If Android builds are required, explicitly review and accept SDK licenses, install Android SDK platforms and run `flutter build apk --debug --no-pub`.
5. From dot run `gh codespace list` and `gh codespace ssh -c NEW_CODESPACE_NAME`, verify commands and keep dev ports private.
6. Record exact errors and fixes in this file; do not deploy to production or touch the older Codespace's local work.
