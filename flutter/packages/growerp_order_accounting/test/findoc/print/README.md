# NASQO Arabic invoice test gates

Development branch only. Never run these tests against production PostgreSQL or send real emails.

## Execute when Flutter SDK is available

From flutter/packages/growerp_order_accounting:

```sh
flutter pub get
flutter test test/findoc/print/
```

The source branch does not include a Flutter SDK. As of 2026-11-11 these tests have NOT run.

## Coverage status

- INV-01: invoice ID, date, document type — data-contract test.
- INV-02: no invented warehouse, expiry or sale UOM — data-contract test.
- INV-03: authoritative server total is preserved — data-contract test.
- INV-04: payment reconciliation (no double counting) — data-contract test; tax/discount breakdown remains unverified.
- INV-05: Arabic amount-in-words and currency fractions — SKIPPED; not implemented.
- INV-06: invoice ID unchanged in data; visual bidi PDF proof remains outstanding.
- INV-07: mixed Arabic/Latin product description preserved in data; visual bidi proof outstanding.
- INV-08: PDF embedded font, Arabic shaping and blue header — SKIPPED pending generated PDF inspection.
- INV-09: 45 items preserved; repeated PDF table header not verified.
- INV-10: A4 bounds and clipping — SKIPPED pending generated PDF inspection.
- INV-11: six sales/purchase/cash/credit/return title combinations — data-contract test.
- INV-12: legacy/Arabic runtime selection — SKIPPED pending integration test.

## Important implementation limits

1. Arabic renderer is disabled by default via NASQO_AR_INVOICE_PDF compile-time flag.
2. No warehouse, lot expiry or sale unit is inferred from unrelated model fields.
3. Financial document must have authoritative grandTotal; currency currently comes from company currency at the legacy entry point and must be verified against the invoice's actual currency before rollout.
4. Arabic amount-in-words, taxes/discount breakdown, paid/remaining, and full reference-template parity remain unfinished.
5. Source code has not been compiled; run Flutter analyzer/tests in isolated CI before merging.
6. The source reference template image is not embedded in this repository.
7. Do not merge or deploy until all INV gates have real assertions and pass, visual PDF review is complete, backups and rollback are verified, and the user gives final deployment approval.
