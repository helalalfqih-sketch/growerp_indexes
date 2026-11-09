# Historical accounting voucher drafts

An owner-authorized continuation can create proposed native Payment documents only when a source voucher explicitly names an already imported invoice. The source account and currency must agree; invoice type establishes direction, and the voucher must have the opposite single amount side. References to returns, unknown invoices, cashless movements, earlier dates, adjustments, and aggregate amounts exceeding the invoice remain held. This does not establish a general debit/credit interpretation.

The importer requires the current company administrator, existing setup-domain write authorization, immutable source/plan/currency hashes, and a private owner-instruction file with scope `EXACT_INVOICE_LINKED_DRAFTS_ONLY`. The private instruction records authorization to create drafts, not approval of financial mappings. No private data belongs in this repository.

Payments remain `PmntProposed`, have no payment instrument, gateway, method, financial account, or payment application. Entity-auto creation preserves the unknown instrument rather than accepting the high-level payment service's company-check default. Source cashbox identifiers and invoice references remain traceable in the import registry. Guards block execution/status advancement and payment applications until a separate financial review.

The company/source-ID registry and unique payment reference prevent retries from duplicating documents. Existing drafts must match amounts, parties, dates, currency, source fingerprints, and invoice references. Modes: check, transaction rollback/idempotency/guard test, apply, and verify. No invoices are marked paid and no cash/bank transfer is executed.

Validation: synthetic plan tests cover both directions and mismatched references/accounts/currencies, dates, adjustments, returns and aggregate limits. On the VPS, rollback verifies native creation, idempotency and guards; apply is followed by full verification, zero ledger/application checks, and the native financial-document service.
