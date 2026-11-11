# NASQO product CSV export — handoff (2026-11-11)

## Scope and source
- Isolated worktree/branch: `fix/nasqo-product-csv-export-20261111` at `dev/nasqo-product-csv-export-20261111`, based on production source `a916544eb287ab259677dd33e0ce67646a3d513b`.
- No GitHub push/merge, production deployment, DB changes or live email tests.
- Screenshot: Arabic product import/export dialog. Requested device CSV save and email CSV export.

## Implementation (uncommitted)
- `flutter/packages/growerp_catalog/lib/src/product/views/product_files_dialog.dart`: separate device-download button, UTF-8 CSV save via existing FilePicker.saveFile(bytes), existing email button retained, save failures surfaced without falsely claiming success.
- `flutter/packages/growerp_catalog/lib/src/product/blocs/product_{event,state,bloc}.dart`: separate `ProductDownloadToDevice` event, downloaded state with CSV payload; fail closed on empty/failed export.
- `flutter/packages/growerp_models/lib/src/rest_client/catalog_api.dart`: authenticated GET products/csv client method.
- `backend/service/growerp.rest.xml`: GET `/rest/s1/growerp/100/ImportExport/products/csv`, require-authentication=true.
- `backend/service/growerp/100/ImportExportServices100.xml`: use existing CSV producer with applicationId and 9999-row cap, product-only email missing-address and emailSent=false failure handling. No actual email sent by this change.
- `flutter/packages/growerp_catalog/test/product/product_bloc_test.dart`: 3 new device-download cases, success/empty/API failure and no email dispatch.

## Verification
- PASS: `git diff --check`, Python stdlib XML parse for both backend XML files, static assertions for auth endpoint, limit and tests.
- NOT RUN: Flutter analyzer, Flutter widget/Bloc tests, Android tests, Web build, backend Moqui service tests, mail integration, real device/browser save; Flutter/Dart absent on server and only ~1.2 GiB disk free.
- Current production web image `nasqo/web:arabic-ledger-a916544-all-assets-20261111` remained healthy.

## Release blockers / review risks
1. Confirm `CatalogServices100.get#Product` enforces caller's tenant/applicationId and user authorization for the new GET route. Authentication alone is insufficient. Test cross-tenant denial in isolated backend.
2. Confirm response format of Moqui REST `get#ExportProducts` (`fileText`) matches `Future<String>` client contract; existing GL account endpoint is a useful precedent but is not proof. Verify UTF-8 BOM/Arabic and CSV formula injection handling where spreadsheet apps are used.
3. Verify 9999-row cap/pagination or fail closed if dataset exceeds cap; never silently truncate customer exports.
4. Verify email template `GrowerpExportProducts` and actual transport configuration in isolated environment. `emailSent` indicates mail service accepted request, not recipient delivery. Do not send test email to real users.
5. Run Flutter codegen, analyzer, focused tests, Android file save and Web download on isolated runner. Ensure `FilePicker.saveFile` support on targeted mobile OS and browsers.
6. Review localization: new download label is bilingual via locale check; prefer ARB localization keys in follow-up when Flutter codegen is available.
7. Do not deploy until all gates green and user-approved release candidate with rollback.

## Rollback
- Worktree changes are isolated; do not cherry-pick to production before validation.
- Preserve current compose and web image digest. Any later release must preserve existing Flutter asset directory permission fixes.

## Follow-up hardening (same day)
- Confirmed underlying `CatalogServices100.get#Product` has `authenticate="false"` and an anonymous first-company fallback: **critical export privacy risk**. Added explicit signed-in user check, resolved company/owner check, and conservative fail-closed 9999-item limit before producing CSV. Must still verify actual tenant permission policies with backend integration tests; a resolved owner is not a substitute for a permission test.
- Adjusted Flutter CSV API to accept the Moqui service response shape (`{fileText: ...}`) or a raw string, and reject unexpected/empty payloads. Added test fixture with `fileText` map.
- Verified XML parse and `git diff --check` again. No Flutter SDK available and no authenticated backend staging test performed.
- The backend email template path references `GrowerpExportProducts`, while the NASQO seed includes `NASQOExportProducts`; verify actual runtime template registration before declaring email fixed.
- **NO-GO** until tenant-denial tests, full-data pagination, actual file save on Android/Web, Flutter analyzer, backend mail integration and isolated build pass. No production change.

## 2026-11-11 continued verification (latest)
- Rechecked the product email template reference: the NASQO seed defines `NASQOExportProducts`, not `GrowerpExportProducts`. Updated product-only template selection accordingly; did not change templates for other entity types.
- The underlying `send#CheckedEmailTemplate` calls `send#EmailTemplate` asynchronously and sets `emailSent=true` on enqueue, **not on delivery**. Updated ProductBloc success message to say queued, and changed failed-send message accordingly. No real mail sent.
- Product email path now also checks signed-in identity and resolved owner/company, as device export does.
- Added `tests/test_product_csv_export_contract.py` with six offline structural tests; `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s tests -p test_*.py -v` passed 6/6. XML parses and `git diff --check` pass.
- **Android/Web/Flutter runtime and backend Moqui tests NOT RUN:** no Flutter/Dart/Java/Gradle SDK on server; only ~1.1 GiB free. No isolated runner with appropriate toolchains is connected to this branch; don't claim runtime success.
- Release blockers: (1) prove cross-tenant authorization with authenticated staging accounts; (2) verify Moqui REST JSON shape and Retrofit generated client; (3) test file picker save on Android/Web; (4) verify email queue/SMTP and template in staging, without customer mail; (5) test CSV quoting/UTF-8/formula-injection and >9999-row behavior; (6) codegen/analyzer/Bloc/widget tests and release web/Android builds. No GO until all pass.
- Branch work remains uncommitted. Production remains unchanged and healthy. Do not push/merge/deploy without explicit approval and passing gates.

## Isolated CI prepared (2026-11-11, later)
- Added `.github/workflows/nasqo-product-csv-isolated-ci.yml` (manual `workflow_dispatch` only, `contents: read`, no deploy/secrets).
- GitHub-hosted Ubuntu jobs: `flutter-web` bootstraps pinned Flutter 3.47.9, runs six offline backend contract checks, analyzes `growerp_catalog` and `growerp_models`, runs product Bloc tests, and compiles Web; `android-compile` sets up JDK 17, bootstraps Flutter, and compiles an unsigned/debug arm64 APK without distribution.
- Validated workflow YAML parses with PyYAML and contains both jobs. Offline Python tests 6/6 passed, `git diff --check` passed.
- NOT EXECUTED on GitHub: the branch and workflow are only in the isolated local worktree. Running GitHub-hosted CI requires committing/pushing to a private feature branch and manual dispatch, which was not authorized. Do not claim Flutter/Android/Web pass.
- Compile-only Android/Web gates do not prove on-device file saving or email delivery; follow with isolated Android device/browser and backend integration tests, including tenant authorization and SMTP queue handling.
- Production remains unchanged and healthy; no DB access or writes.

## Production-deployment request and blocked gates (2026-11-11)
- User authorized deployment to erp.indexesstore.com ONLY after Flutter, Android, Web, Backend and security tests pass, with no DB changes.
- Rechecked: 6/6 offline Python contracts passed, git diff --check passed, CI workflow file exists.
- No Flutter/Dart/Java/Gradle on this host; ~1.1 GiB disk free. Do not install/build on this production-adjacent host.
- GitHub CI has NOT run for this local-only uncommitted branch. Separate approval required to commit/push branch and dispatch CI; do not treat deployment approval as permission to bypass this.
- Mandatory blockers remain: Flutter analyzer, Bloc/widget tests, Android and Web builds plus actual save behavior, authenticated backend REST/tenant-isolation tests, SMTP queue/template integration in isolated environment, and CSV completeness/security review.
- Production web remains healthy; no deployment, database write, GitHub push, merge or restart. Decision: NO-GO.
