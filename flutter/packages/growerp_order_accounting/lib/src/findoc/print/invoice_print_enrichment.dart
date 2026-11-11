import 'package:decimal/decimal.dart';

/// Verified print-only fields. A trusted backend adapter must validate tenant,
/// invoice ownership, shipment/lot linkage, and payment application status.
/// Do not populate this from a generic product or warehouse lookup.
class InvoicePrintEnrichment {
  const InvoicePrintEnrichment({
    required this.invoiceId,
    this.warehouseName,
    this.facilityId,
    this.linesByItemSeqId = const {},
    this.paidTotal,
    this.remainingTotal,
    this.paymentEvidenceId,
  });

  /// Must match the invoice's real backend invoiceId (not a pseudo number).
  final String invoiceId;
  final String? warehouseName;
  final String? facilityId;
  final Map<String, VerifiedInvoiceLinePrintData> linesByItemSeqId;
  final Decimal? paidTotal;
  final Decimal? remainingTotal;

  /// Backend evidence identifying a validated invoice payment application set.
  final String? paymentEvidenceId;
}

class VerifiedInvoiceLinePrintData {
  const VerifiedInvoiceLinePrintData({
    this.saleUomId,
    this.saleUnitName,
    this.expiryDate,
    this.verifiedLotAssetId,
  });

  /// The actual invoice line's sale UOM; never product.amountUom.
  final String? saleUomId;
  final String? saleUnitName;

  /// Only a verified inventory lot/asset allocated to this exact invoice line.
  final DateTime? expiryDate;
  final String? verifiedLotAssetId;
}
