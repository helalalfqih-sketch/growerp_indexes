# NASQO invoice supplemental fields — backend source audit (2026-11-11)

Source reviewed read-only: repository revision b0692d9. Backend services are
not modified. This branch adds a print-only, opt-in verified enrichment DTO,
not a new REST endpoint or any database migration.

## Current REST shape

- `backend/service/growerp/100/FinDocServices100.xml`, get#FinDoc at ~219:
  output contract includes invoiceId, pseudoId, creationDate, grandTotal,
  originalCurrency, itemSeqId, product, description, quantity and price.
  It does NOT expose invoice warehouse, lot expiry, actual line sale UOM,
  applied payment total or remaining invoice balance.
- Invoice mapping at ~805–916 builds finDocOut from InvoiceItemsAndParties,
  invoiceTotal, invoiceDate and item.amount/quantity. Payment application
  data is not mapped to invoice print output.

## Field-by-field evidence and proposed trusted adapter

1. Warehouse: `InventoryServices100.get#Location` at ~18 returns locationId,
   locationName and assets, accepts facilityId. `FinDocServices100.create#Order`
   at ~1839/1848 and create#Shipment at ~2025 use facility identifiers.
   A facility/location name is not sufficient proof of the warehouse for an
   invoice: backend must resolve invoice -> shipment/order -> facility for
   this tenant and document. Enrichment requires facilityId with warehouseName.
2. Expiry: `InventoryServices100.get#Assets` at ~277 exposes
   expectedEndOfLifeDate, NOT confirmed invoice-line batch expiry. Do not
   equate this asset field with product expiry. Adapter must prove exact
   invoiceItemSeqId -> allocated lot/asset -> expiry before supplying it.
3. Sale UOM: `CatalogServices100.xml` at ~453 and ~638 uses
   quantityUomId = OTH_ea for some product creation paths. This is not
   proof of actual invoice-line UOM. `FinDocItem` lacks saleUomId. Adapter
   must retrieve authoritative line UOM ID and label. Never use
   Product.amountUom as a fallback.
4. Paid: `PosServices100.xml` at ~490 retrieves Payment records with
   forInvoiceId and reports tender amounts for POS receipts. Stripe import
   in `AccountingServices100.xml` at ~2627 calls
   mantle.account.PaymentServices.apply#PaymentForInvoice after linking
   payment to invoice. These are separate payment mechanisms. Print totals
   must derive from *applied/posted* payment records, scoped to tenant,
   invoice, currency, excluding voids/refunds and duplicate applications.
5. Remaining: calculate only from authoritative invoice total and validated
   applied payment total in the same currency, or retrieve an authoritative
   invoice outstanding balance. Do not treat party account balance as invoice
   remaining. Current print enrichment requires paid + remaining == total.
   Partial refunds, credits, and FX require separate validated treatment.

## Branch integration

- `InvoicePrintEnrichment` is an immutable DTO carrying backend invoiceId,
  verified facility/warehouse, verified per-line UOM/lot expiry, and payment
  totals with application evidence ID.
- `InvoicePrintData.fromFinDoc` rejects invoice mismatches, missing facility
  provenance, unknown itemSeqId, incomplete UOM pairs, expiry without lot
  evidence, and payment totals without application evidence or reconciliation.
- `NasqoInvoicePdfRenderer.render(enrichment: ...)` displays verified fields
  only. Existing callers pass no enrichment; missing fields remain hidden.
- This is *validation of supplied evidence identifiers*, not cryptographic or
  backend verification. The future trusted backend adapter MUST validate
  evidence ownership, status and allocation before constructing this DTO.
- The existing legacy `verifiedWarehouseName` argument is retained for
  compatibility; do not use it in new integrations. The enrichment path
  rejects conflicting warehouse sources.
- No backend endpoint, REST model, UI route or production image changed.

## Next implementation gate

1. Identify actual entities/joins and payment statuses in the deployed Moqui
   schema, including tenant filters and currency. Do not infer from naming.
2. Implement a read-only backend service returning the five fields and
   provenance, only after explicit separate approval to change backend code.
3. Add integration tests for cross-tenant denial, mismatched invoice/line,
   multi-lot invoice lines, FX, partial payments, void/refund and duplication.
4. Run Flutter analyzer/tests in isolated CI. This host has no Flutter SDK.
5. Keep Arabic PDF feature flag disabled until end-to-end evidence is verified.
