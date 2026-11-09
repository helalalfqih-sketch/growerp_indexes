# Smart Accountant accounting source reconciliation

This remains a review-only component for the existing NASQO company's accounting module.
It does not create invoices, payments, accounts, inventory or GL entries.

## Source structure
- `customers.type` references `boxes.id`: named, user-defined account groups.
- `customers` mixes counterparties and internal account records. Do not turn every record into a customer.
- `cboxes` contains cashboxes. Do not add `boxes` to the cashbox count.
- `macc` is referenced by `meters.acc_id` and has dimension labels; its empty row count is not evidence of a missing GL chart.

## Run on the authorized server
Use the NASQO backend's Groovy/JDBC classpath. No extra database or dependency is required.
The runner accepts source path, target company ID and the reconciliation helper path.
The source directory must be private (0700). It writes `reconciliation-v2.json` (0400) there.
An identical rerun is accepted; a different existing report is rejected.
Never commit source backups or generated reports to GitHub.

## Meaning of the report
All amounts use exact BigDecimal arithmetic. Raw dain/madin remain source columns,
not approved ledger debit/credit. Balances stay separate for each source currency.
The pre-2026 split is comparison evidence for the user's report period, not an approved cutover
or an instruction to add new opening balances.
Cash-linked one-sided vouchers, opening labels, exchange labels, account transfer labels,
zero amounts and no-cashbox exceptions remain separate categories.
Transfer pairing uses explicit source account names and adjacency when unique.
It is a review candidate; unmatched partners, dates and amount differences are retained.
No balancing adjustment is invented.

Stable company/system/table/source-ID keys and row hashes are a specification for the
later importer's idempotency checks. They do not implement a PostgreSQL duplicate constraint.
A changed source row retains its identity and has a different fingerprint.

Before any accounting writes, resolve missing currencies, transfer differences, account and
cashbox mappings, business scope, full-history versus cutover balances and source voucher direction.
The runner never enables financial writes even when no source-format errors are found.

## Validation
`tools/test_smart_accountant_reconciliation.groovy` takes preview-helper and reconciliation-helper
paths. It exercises synthetic SQLite with the production Groovy/JDBC runtime: source grouping,
cashbox separation, measurement accounts, exact decimal arithmetic, opening/zero/transfer
classification, currency exceptions, changed-row detection, company isolation and read-only source hashes.
Existing Python CI protects the preview/service contracts. It is separate from this runtime test.
