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

## Next phase (not implemented)

A separate, tenant-scoped accounting migration ingestion service within the **current NASQO deployment** should store a private review manifest and migration keys, validate mappings and duplicate reconciliation, then create strictly unposted documents only after explicit approval. It must never call `post#AcctgTrans` implicitly.
