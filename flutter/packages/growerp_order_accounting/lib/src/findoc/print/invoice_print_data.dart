import 'invoice_print_enrichment.dart';
import 'invoice_print_identity.dart';
import 'package:decimal/decimal.dart';
import 'package:growerp_models/growerp_models.dart';

/// Immutable, non-posting snapshot of verified invoice display data.
class InvoicePrintData {
  const InvoicePrintData({
    required this.invoiceNumber,
    required this.createdAt,
    required this.title,
    required this.currencyId,
    required this.authoritativeTotal,
    required this.lines,
    this.warehouseName,
    this.paidTotal,
    this.remainingTotal,
  });

  final String invoiceNumber;
  final DateTime createdAt;
  final String title;
  final String currencyId;
  final Decimal authoritativeTotal;
  final List<InvoicePrintLine> lines;
  final String? warehouseName;
  final Decimal? paidTotal;
  final Decimal? remainingTotal;

  static InvoicePrintData fromFinDoc({
    required FinDoc document,
    required String verifiedCurrencyId,
    String? verifiedWarehouseName,
    Decimal? verifiedPaidTotal,
    Decimal? verifiedRemainingTotal,
    InvoicePrintEnrichment? enrichment,
    InvoicePrintIdentity? verifiedIdentity,
  }) {
    if (document.docType != FinDocType.invoice) {
      throw ArgumentError('Document must be an invoice');
    }
    final id = document.pseudoId ?? document.invoiceId;
    if (id == null || id.trim().isEmpty) {
      throw StateError('Invoice number is missing');
    }
    if (document.creationDate == null) {
      throw StateError('Invoice creation date is missing');
    }
    if (document.grandTotal == null) {
      throw StateError('Authoritative invoice total is missing');
    }
    if (verifiedCurrencyId.trim().isEmpty) {
      throw StateError('Verified currency is missing');
    }
    if (verifiedIdentity != null) {
      verifiedIdentity.validate(document);
      if (verifiedCurrencyId != verifiedIdentity.currencyId) {
        throw StateError('Currency conflicts with verified invoice identity');
      }
    }
    if (enrichment != null) {
      if (verifiedWarehouseName?.trim().isNotEmpty == true) {
        throw StateError('Use one verified warehouse source only');
      }
      if (document.invoiceId == null || document.invoiceId != enrichment.invoiceId) {
        throw StateError('Enrichment invoiceId does not match backend invoice');
      }
      if (enrichment.warehouseName?.trim().isNotEmpty == true &&
          enrichment.facilityId?.trim().isNotEmpty != true) {
        throw StateError('Warehouse name requires verified facilityId');
      }
      if (enrichment.paidTotal != null || enrichment.remainingTotal != null) {
        if (verifiedPaidTotal != null || verifiedRemainingTotal != null) {
          throw StateError('Conflicting payment totals');
        }
        if (enrichment.paymentEvidenceId?.trim().isNotEmpty != true) {
          throw StateError('Payment totals require application evidence');
        }
      }
      final itemIds = document.items.map((item) => item.itemSeqId).toSet();
      for (final entry in enrichment.linesByItemSeqId.entries) {
        if (!itemIds.contains(entry.key) || entry.key.trim().isEmpty) {
          throw StateError('Unmatched invoice line in enrichment');
        }
        final extra = entry.value;
        if ((extra.saleUnitName?.trim().isNotEmpty == true) !=
            (extra.saleUomId?.trim().isNotEmpty == true)) {
          throw StateError('Sale UOM requires both ID and name');
        }
        if (extra.expiryDate != null &&
            extra.verifiedLotAssetId?.trim().isNotEmpty != true) {
          throw StateError('Expiry requires verified allocated lot/asset');
        }
      }
    }
    final paid = enrichment?.paidTotal ?? verifiedPaidTotal;
    final remaining = enrichment?.remainingTotal ?? verifiedRemainingTotal;
    if ((paid == null) != (remaining == null)) {
      throw StateError('Paid and remaining totals must be supplied together');
    }
    if (paid != null &&
        paid + remaining! != document.grandTotal!) {
      throw StateError('Payment reconciliation does not match invoice total');
    }
    return InvoicePrintData(
      invoiceNumber: id,
      createdAt: document.creationDate!,
      title: verifiedIdentity?.title ?? _title(document),
      currencyId: verifiedCurrencyId,
      authoritativeTotal: document.grandTotal!,
      warehouseName: enrichment?.warehouseName?.trim().isNotEmpty == true
          ? enrichment!.warehouseName!.trim()
          : verifiedWarehouseName?.trim().isNotEmpty == true
              ? verifiedWarehouseName!.trim() : null,
      paidTotal: paid,
      remainingTotal: remaining,
      lines: List.unmodifiable(document.items.map((item) {
        final extra = enrichment?.linesByItemSeqId[item.itemSeqId];
        return InvoicePrintLine(
          description: item.description ?? item.product?.productName ?? '',
          quantity: item.quantity,
          price: item.price,
          // Only exact invoice-line verified sale UOM and allocated lot expiry.
          unitName: extra?.saleUnitName,
          expiryDate: extra?.expiryDate,
        );
      })),
    );
  }

  static String _title(FinDoc document) {
    final type = document.sales ? 'بيع' : 'شراء';
    final subtype = (document.docSubType ?? '').toLowerCase();
    if (subtype.contains('return') || subtype.contains('مرتجع')) {
      return 'فاتورة مرتجع $type';
    }
    final term = subtype.contains('cash') || subtype.contains('نقد')
        ? 'نقدي'
        : subtype.contains('credit') || subtype.contains('آجل')
            ? 'آجل'
            : '';
    return 'فاتورة $type $term'.trim();
  }
}

class InvoicePrintLine {
  const InvoicePrintLine({
    required this.description,
    this.quantity,
    this.price,
    this.unitName,
    this.expiryDate,
  });
  final String description;
  final Decimal? quantity;
  final Decimal? price;
  final String? unitName;
  final DateTime? expiryDate;
  Decimal? get calculatedTotal =>
      quantity == null || price == null ? null : quantity! * price!;
}
