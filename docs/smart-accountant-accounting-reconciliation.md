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

## Approved single-currency preparation

An operator can record the owner's explicit currency decision in the same private
source directory as `import-decisions-v1.json`. The company and source hash must
match the verified reconciliation. The supported policy is
`SINGLE_CURRENCY_AS_DECLARED`: source IDs 1, 2 and 3 are interpreted as YER,
numeric amounts are preserved, and no exchange conversion is performed. Original
currency IDs remain on every prepared movement and line.

`tools/run_accounting_draft_plan.groovy source.back companyId helper.groovy`
uses `tools/NasqoAccountingDraftPlan.groovy` to create private, immutable
`draft-plan-v1.json` and `draft-plan-manifest-v1.json`. The source is read-only.
The runner verifies owner-decision scope and the document labels from the prior
source-report matching evidence. It rejects duplicate source identities, orphan
lines, unsupported currencies, unproven labels and changed existing outputs.
Repeated identical preparation produces identical files.

These are **import preparation drafts**, not NASQO Invoice, Payment or AcctgTrans
records. All drafts retain `PREPARED_REQUIRES_MAPPING` and
`readyForFinancialCreation=false`. Account mappings, voucher directions,
opening balances, source exchange entries and transfer exceptions remain
explicit holds. The code never creates balancing entries or marks source
amounts as approved ledger debit/credit.

The authenticated saved-review service exposes only approved currency metadata,
preparation counts and source document labels. It verifies the source,
decision and prepared-file hashes and never returns the raw plan or private
file paths. The UI explicitly distinguishes preparation from actual financial
record creation.

Validation: synthetic Groovy tests cover decision/company isolation, preserved
amounts, source identity, document evidence, orphan lines, immutable reruns,
projection integrity and retained holds. Node tests cover rendering guards and
prevent a prepared response from claiming a financial import.
