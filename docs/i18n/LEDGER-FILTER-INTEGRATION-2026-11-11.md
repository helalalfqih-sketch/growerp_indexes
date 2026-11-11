# NASQO Arabic + ledger filters integration — 2026-11-11

## Provenance
- Ledger production release: `nasqo/web:ledger-filters-782fde06-20261111`.
- Exact source commit: `782fde06e51d2094e736729b2350567188e2a128` (PR #39).
- Localization branch before merge: `feature/nasqo-flutter-arabic-all-20261111`, head `ff9d3a99796ac394f14920f6d97622c483ac1434`.
- PR #39 changes were transplanted **without the obsolete .github/workflows/nasqo-admin-web-build.yml** because that workflow was deleted in the newer master branch. Existing localization CI was extended with explicit ledger/RTL tests.

## Exact merged changes
- `growerp_core`: StyledDataTable contextual empty message, clear-filter action, RTL widget test.
- `growerp_order_accounting`: Arabic/English ARB keys for no-results, clearing filters, account sort labels; display-only ledger filter helper, fiscal-year navigation and sorting in balance summary, journal and document list filter empty states; unit test.
- Existing Arabic localization edits in the invoice item dialog, splash screen, balance summary tooltip and revenue/expense chart remain intact.

## Conflict resolution
- `balance_summary_list.dart`: previous-year tooltip was already localized on the Arabic branch; upstream PR #39 uses `_localizations.previousYear`. Kept the upstream getter because it is already initialized in the view and preserves the exact translated value.
- Removed cherry-pick conflict with deleted `nasqo-admin-web-build.yml` by retaining deletion and extending `nasqo-flutter-arabic-release-gate.yml` instead.

## Release restrictions
- Run GitHub Actions analysis, tests and Admin Web build for the new combined SHA; the previous successful CI artifact `43040069173` is **obsolete for deployment**.
- Do not deploy until the new build passes and the Web image is layered with production `accounting-review.js`, `session-resume.js`, branded `index.html`, Android downloads and `.well-known` assets.
- Production Web image rollback reference: `sha256:f603151369a31a2151462e8542ffcae175c0d13237741d6eb7c3316f975620e5`.
- No backend/database or production container changes in this integration.
