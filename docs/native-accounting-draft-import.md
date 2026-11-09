# Native accounting draft import

The Smart Accountant source is migrated only into accounting documents. The approved
single-currency policy preserves numeric values in YER. No source backup, customer
names, or private approvals are stored in this repository.

The administrator-only POST endpoint at
`/rest/s1/growerp/100/ImportExport/AccountingDrafts` uses the existing setup-domain
write authorization and derives the company from the authenticated administrator's
employment relation. It additionally requires the configured staging company and a
private, source-hash-bound draft approval. It accepts `check`, `rollback`, `apply`,
and `verify`. There are no new permission grants.

Only approved source counterparties and proven invoice types are eligible. Headers
and every quantity × price must reconcile exactly. Missing descriptions, adjustments,
opening balances, transfers, vouchers without approved direction, and unmapped
internal or aggregate accounts remain held. Products are descriptions/references,
not catalog or inventory imports. Counterparty organization records are financial
account labels, not assertions about a counterparty's legal classification.

Native invoices use InvoiceInProcess/InvoiceIncoming and preserve dates, quantities,
unit prices, and source references. Returns reverse the parties and use InvoiceReturn.
Cash settlement information is retained in the private source registry; no Payments,
PaymentApplications, inventory transactions, tax calculations, or ledger entries
are created. These drafts therefore do not represent migrated outstanding balances.

AccountingImportDocument has a company/source-ID primary key and a unique invoice
reference. Batches are transactional, resumed imports verify existing drafts, and
source changes or unregistered references fail closed. An Invoice EECA blocks
finalizing imported drafts until their accounting review is explicitly approved.
No API for granting that approval is added here.

The maintenance runner authorizes the registered REST endpoint with the existing
authorization guard before calling the service. It uses a separate transaction
journal and disables scheduled jobs and startup data loading in its own process.
It does not disable authorization, install grants, or expose credentials.

Before apply: take and validate a database backup, run the pure planner tests, and
run `rollback`. The rollback mode creates one invoice per verified type, checks
idempotency and posting rejection, and rolls all financial/counterparty changes
back. Deploy the guard and new backend before apply. Run `verify` after apply and
independently confirm native invoice totals, statuses, company scope, zero ledger
and payment creation, and the unchanged source hash.
