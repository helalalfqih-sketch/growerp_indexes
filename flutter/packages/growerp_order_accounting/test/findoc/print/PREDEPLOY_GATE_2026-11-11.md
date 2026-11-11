# NASQO Arabic PDF predeployment gate — 2026-11-11

**Decision: NO-GO. No production deploy, merge, migration, restart or DB write.**

## Observed production baseline (read-only)

- Web: `nasqo-production-web-1`, image
  `nasqo/web:quill-i18n-candidate-20261111`, digest
  `sha256:ef144ee3aa9a175f5c5216a286a49607c88f93b926cd231cb9f75db1d577e142`
- Backend: `nasqo-production-backend-1`, image
  `nasqo/backend:catalog-dashboard-visible-20261110`, digest
  `sha256:6c289d809909747190588e233e96ecf5b5c768abf8998ef75bed586c5f015ea2`
- Both containers healthy during audit.
- No production image tags, compose files, secrets or database settings changed.

## Backup checks (read-only)

- `/home/chatgpt-admin/nasqo-cutover/backups/nasqo-20261111T003001Z.dump`
- SHA-256 sidecar: **PASS** (`sha256sum -c`).
- PostgreSQL custom dump, created 2026-11-11 00:30:01 UTC, database
  version 17.2, 5,736 TOC entries.
- `postgres:17.2` offline container, `--network none --read-only`,
  backup mounted read-only: `pg_restore --list` **PASS**.
- Entire dump decompression/SQL stream with
  `pg_restore --file=/dev/null`: **PASS**.
- **Not verified:** restore into an independent PostgreSQL instance,
  schema/data checks, application login, accounting reconciliation.
  Therefore backup recovery is NOT approved.

## CI gate

- Flutter and Dart SDK absent from server. Only ~2.2 GB disk available.
- `run_isolated_pdf_tests.sh` correctly refuses local execution (exit 2).
- New `.github/workflows/nasqo-arabic-invoice-pdf-ci.yml` is limited to
  feature branch, read-only repository permission, isolated Ubuntu runner,
  Flutter 3.47.9, Python venv, analyzer/tests, generated PDF audit, artifacts.
- **CI has not run.** The feature branch does not exist on GitHub origin.
  Remote git push dry run failed with missing HTTPS credentials.
  No push was attempted. Do not claim green CI.
- Four INV contract tests remain skipped (05, 08, 10, 12).
- The source of trusted invoice currency/kind and paid/remaining remains
  unimplemented in the backend; opt-in Arabic print must fail closed.

## Rollback plan (NOT executed)

1. Before any future approved release, record live compose configuration,
   image digests, data migration plan and current database backup.
2. Prove restoration to a **separate** database on a different runner or
   isolated storage. Verify tenant scope, key accounting balances, invoices,
   payment applications and application startup.
3. Keep the exact current web/backend image digests available, including
   any app assets or volumes that need to be restored.
4. Define a tested procedure to reselect previous image digests and disable
   `NASQO_AR_INVOICE_PDF`, then restart ONLY with final production approval.
5. For any schema/data migration, separately validate backward compatibility
   and restore procedure; container rollback alone is insufficient.
6. Record a rollback trigger (failed invoice print, currency mismatch,
   accounting reconciliation failure, startup/health failure).
7. Obtain a distinct final production approval after all gates pass.

## Blocking acceptance gates

- [ ] Remote isolated CI run succeeded, analyzer and tests green
- [ ] All INV-01..INV-12 tests executable; no skipped required gates
- [ ] Generated A4 PDFs inspected for Arabic glyphs, 45/100 rows and repeats
- [ ] Trusted invoice currency and classification backend source validated
- [ ] Paid/remaining and warehouse/UOM/expiry data integration verified
- [ ] Compare actual production sources and preserve existing fixes
- [ ] Full independent PostgreSQL restore and business reconciliation pass
- [ ] Rollback rehearsed and documented
- [ ] User's separate final production approval

No changes to production were made by this audit.

## CI scheduler failure and correction (2026-11-11)

- First GitHub Actions push run: 43044532570, conclusion failure, **zero jobs**.
- Root cause found in workflow job-level `env`: `${{ runner.temp }}` was used
  twice. GitHub's context availability rules do not permit `runner` at
  `jobs.<job_id>.env`, so workflow validation fails before scheduling jobs.
- Correction: use isolated GitHub-hosted Ubuntu `/tmp/nasqo-*` directories
  in job env, preserving existing `contents: read`, feature branch filter,
  no secrets, and artifact uploads on failure. This is a CI-only change.
- Verified corrected YAML with `actionlint v1.7.12` and YAML parser locally.
- A passing actionlint check is not a substitute for an actual GitHub run.
- Future editors: do not reintroduce `${{ runner.* }}` in job-level env;
  runner context is valid only in supported step-level contexts.
