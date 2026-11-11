import 'package:growerp_models/growerp_models.dart';

/// Invoice-only verified display identity, not inferred from payment terms.
enum VerifiedInvoiceKind {
  salesCash,
  salesCredit,
  purchaseCash,
  purchaseCredit,
  salesReturn,
  purchaseReturn,
}

class InvoicePrintIdentity {
  const InvoicePrintIdentity({
    required this.invoiceId,
    required this.currencyId,
    required this.currencyEvidenceId,
    required this.kind,
    required this.kindEvidenceId,
  });

  final String invoiceId;
  /// Invoice transaction/total currency, NOT company default or product currency.
  final String currencyId;
  final String currencyEvidenceId;
  final VerifiedInvoiceKind kind;
  final String kindEvidenceId;

  String get title => switch (kind) {
    VerifiedInvoiceKind.salesCash => 'فاتورة بيع نقدي',
    VerifiedInvoiceKind.salesCredit => 'فاتورة بيع آجل',
    VerifiedInvoiceKind.purchaseCash => 'فاتورة شراء نقدي',
    VerifiedInvoiceKind.purchaseCredit => 'فاتورة شراء آجل',
    VerifiedInvoiceKind.salesReturn => 'فاتورة مرتجع بيع',
    VerifiedInvoiceKind.purchaseReturn => 'فاتورة مرتجع شراء',
  };

  void validate(FinDoc document) {
    if (document.docType != FinDocType.invoice ||
        document.invoiceId == null ||
        invoiceId.trim().isEmpty ||
        document.invoiceId != invoiceId) {
      throw StateError('Verified invoice identity does not match FinDoc');
    }
    if (!RegExp(r'^[A-Z]{3}$').hasMatch(currencyId) ||
        currencyEvidenceId.trim().isEmpty ||
        kindEvidenceId.trim().isEmpty) {
      throw StateError('Verified currency/kind provenance is required');
    }
    final kindSales = switch (kind) {
      VerifiedInvoiceKind.salesCash ||
      VerifiedInvoiceKind.salesCredit ||
      VerifiedInvoiceKind.salesReturn => true,
      _ => false,
    };
    if (document.sales != kindSales) {
      throw StateError('Verified invoice kind conflicts with sales direction');
    }
  }
}
