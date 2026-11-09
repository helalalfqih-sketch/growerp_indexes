# NASQO Accounting: Smart Accountant Pro SQLite migration

**Source product (identified by owner):** المحاسب الذكي برو / Smart Accountant Pro, Android release 135.3.5 listed by the user. The exact release embedded in an individual SQLite backup is **not independently attested** by the file. Its tables and columns, not marketing text, must control the technical mapping.

**Destination:** the **accounting module of the existing NASQO ERP**. This is NOT a whole-application import, a new tenant, a POS clone, or a migration to a second NASQO deployment.

## Intended coverage and authorization

| Legacy source | Accounting destination | Safety rule |
| --- | --- | --- |
| currencies | Monetary units and exchange-rate reconciliation | Approve historical exchange rates before booking balances |
| customers | Receivable/payable counterparties and financial contacts | Approve all numeric role codes; no assumption every party is a customer |
| input_move | Financial document headers: receipts, disbursements, invoices, transfers, journals as proven by source labels | Do not infer meaning of bill_type, bstate, dain or madin |
| bill_details, bill_details2 | Financial document lines and linked quantity/amount references | Match to exact source header and source currency; do not automatically post |
| boxes, cboxes | Cashbox/financial account candidates | Review ownership and balances; do not guess GL accounts |
| macc | Potential source chart-of-accounts records | Empty in inspected backup; use reviewed NASQO accounts and mapping |
| products, stores | Reference records for invoice line and stock valuation matching | Not a full catalog/location import by default |
| store_trans, trans_details | Inventory movement effects on accounting | Resolve source warehouse references and inventory valuation |
| bin and archived bill detail tables | Audit/archive evidence | Never silently treat deleted/archived rows as active invoices |
| offers and extra modules | Non-posting reference/archive evidence | Not in accounting import until role defined |

The app supports **cash and credit sales/purchases, receipts, disbursements, journal vouchers, currency exchanges, multiple cashboxes/accounts, multiple currencies per account, stock and unit sizes, and annual closing**. These capabilities motivate migration coverage, but descriptions do **not** prove which numeric codes in the SQLite file mean each operation.

## Accounting acceptance gates

1. Confirm the authenticated current NASQO business/company scope (accounting target and organization ownership). The observed production party ID must not be confused with a store ID or a separate tenant.
2. Review the source app's own menus or printable report definitions for *each* `input_move.bill_type`, `bstate`, `customers.type`, `boxes` and `cboxes` role.
3. Map source currency IDs, transaction currency and reporting currency. Verify exchange rates and posting dates with the source system.
4. Approve the destination chart of accounts for cash/banks, receivables, payables, sales, purchases, inventory, COGS, discounts, taxes, and exchange gains/losses.
5. Classify historical invoices, vouchers, journals, transfers and archived records; identify duplicated documents already in NASQO via stable source keys.
6. Reconcile the opening balance / closing balance cutoff and compare trial balance, cashbox totals, party balances, inventory valuation and account/currency totals from both systems.
7. First perform a non-mutating dry run; then create only *unposted* financial drafts if explicitly approved. Any posting to the GL requires an additional explicit sign-off.

## Current implementation

The `SqliteImport/Preview` endpoint remains authenticated, read-only and non-posting. The accounting-aware response adds:

- Real Flutter-friendly count aliases `financialMoves`, `billDetails`, `stockMoves`, `storeTransfers` (prevent erroneous zero chips).
- Supplementary bill lines, cashbox and chart-of-accounts source counts.
- Source party-type distributions and unmatched currency references.
- Explicit `accountingReview.targetModule = accounting`, `readyForAccountingImport = false`, `accountingPostingAllowed = false`, `automaticLedgerConversionAllowed = false` and `fullApplicationImportAllowed = false`.

The `accountingReview` object contains only counts/codes, not names, addresses, phone numbers or invoice line data. Product and store counts are *reference evidence*, not authorization to import catalog or logistics.

The Flutter model can parse the accounting-specific review response and the Arabic/English labels now describe accounting import from Smart Accountant Pro rather than a generic old POS. The source product and exact backup release are user-provided assumptions, **not proof** of schema semantics.

**No real financial records are yet imported by this feature; existing NASQO accounting or production data must not be replaced automatically.**

## Tests

- `python3 tools/test_nasqo_accounting_only_preview.py` (synthetic-only source schema and contract).
- `python3 tools/test_nasqo_sqlite_preview_service.py` (existing Groovy/HTTP service contract).
- Groovy runtime validation against a **synthetic** eight-table SQLite fixture on a restricted container before any production image is deployed.
- Manual acceptance: authenticated admin uploads the owner-approved legacy backup, receives counts, and compares source reports.

Never commit the owner's private backup or raw PII to GitHub. Sensitive data is not needed for the tests.

## Current-VPS source staging (opt-in, **not a financial import**)

NASQO's existing admin-only `SqliteImport/Preview` endpoint can persist an
owner-approved source, **only** when the production backend operator sets
`NASQO_ACCOUNTING_STAGE_COMPANY` to the authenticated source company's
`companyPartyId`. If the environment variable is missing or mismatched,
the preview stays ephemeral and leaves no source archive.

On an opt-in preview, the server:

1. Runs the normal read-only SQLite integrity/schema review, and rejects any
   source over 32 MiB for persistent staging.
2. Validates the authenticated server-derived company ID, not any client-provided
   company identifier; never grants staging to another company.
3. Saves the SQLite source at `/var/lib/nasqo-accounting-imports/<companyPartyId>/<sourceSHA256>/source.back`
   in a **private, persistent VPS Docker bind mount**, with owner-only permissions.
4. Saves a small private `manifest.json` containing aggregates, source SHA-256,
   company scope and **`BLOCKED_PENDING_ACCOUNT_MAPPING`**. No customer
   names, phones or financial line data appear in the manifest.
5. Rejects a different second source for the same company until the first is
   explicitly reconciled; repeated identical uploads are idempotent.
6. Appends a visible Arabic warning to the existing preview explaining whether
   the source was prepared, without claiming that GL entries were imported.

**Source .back remains private and never goes to GitHub, client logs or public
web storage. No NASQO PostgreSQL tables are touched by this path.** The user
must reupload the original `.back` through the existing admin preview control
*once* to stage it after the opt-in backend deployment. A prior preview upload
was ephemeral and was deleted.

Before the actual accounting import, resolve source bill types, status codes,
party roles, currencies, cashboxes, GL accounts, duplicate invoices, opening
balances and trial-balance reconciliation. Posting is blocked until these
mappings and totals are explicitly approved.

## Next phase (not implemented)

A separate, tenant-scoped accounting migration ingestion service within the **current NASQO deployment** should store a private review manifest and migration keys, validate mappings and duplicate reconciliation, then create strictly unposted documents only after explicit approval. It must never call `post#AcctgTrans` implicitly.
