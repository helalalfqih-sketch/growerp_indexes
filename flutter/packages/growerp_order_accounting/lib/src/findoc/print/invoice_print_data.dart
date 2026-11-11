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
    if ((verifiedPaidTotal == null) != (verifiedRemainingTotal == null)) {
      throw StateError('Paid and remaining totals must be supplied together');
    }
    if (verifiedPaidTotal != null &&
        verifiedPaidTotal + verifiedRemainingTotal! != document.grandTotal!) {
      throw StateError('Payment reconciliation does not match invoice total');
    }
    return InvoicePrintData(
      invoiceNumber: id,
      createdAt: document.creationDate!,
      title: _title(document),
      currencyId: verifiedCurrencyId,
      authoritativeTotal: document.grandTotal!,
      warehouseName: verifiedWarehouseName?.trim().isNotEmpty == true
          ? verifiedWarehouseName!.trim() : null,
      paidTotal: verifiedPaidTotal,
      remainingTotal: verifiedRemainingTotal,
      lines: List.unmodifiable(document.items.map((item) => InvoicePrintLine(
        description: item.description ?? item.product?.productName ?? '',
        quantity: item.quantity,
        price: item.price,
        // Do not infer sale UOM from product.amountUom.
        unitName: null,
        expiryDate: null,
      ))),
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
