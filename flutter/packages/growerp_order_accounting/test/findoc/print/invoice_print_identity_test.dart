import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_data.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_identity.dart';

FinDoc doc({bool sales = false, String? subtype}) => FinDoc(
  docType: FinDocType.invoice,
  invoiceId: 'REAL-42',
  pseudoId: '348',
  sales: sales,
  docSubType: subtype,
  creationDate: DateTime.utc(2026, 11, 6),
  grandTotal: Decimal.parse('63000'),
);

InvoicePrintIdentity identity({
  String invoiceId = 'REAL-42',
  String currency = 'YER',
  String currencyEvidence = 'ledger:REAL-42',
  VerifiedInvoiceKind kind = VerifiedInvoiceKind.purchaseCredit,
  String kindEvidence = 'terms:REAL-42',
}) => InvoicePrintIdentity(
  invoiceId: invoiceId,
  currencyId: currency,
  currencyEvidenceId: currencyEvidence,
  kind: kind,
  kindEvidenceId: kindEvidence,
);

void main() {
  test('P0: invoice currency is not company or product default', () {
    final result = InvoicePrintData.fromFinDoc(
      document: doc(),
      verifiedCurrencyId: 'YER',
      verifiedIdentity: identity(),
    );
    expect(result.currencyId, 'YER');
    expect(result.title, 'فاتورة شراء آجل');
  });

  test('P0: reject mismatched invoice ID', () {
    expect(() => identity(invoiceId: 'OTHER').validate(doc()), throwsStateError);
  });

  test('P0: reject missing or malformed currency provenance', () {
    expect(() => identity(currencyEvidence: '').validate(doc()), throwsStateError);
    expect(() => identity(currency: 'YE').validate(doc()), throwsStateError);
    expect(() => identity(currency: 'yer').validate(doc()), throwsStateError);
    expect(() => identity(kindEvidence: '').validate(doc()), throwsStateError);
  });

  test('P0: reject currency mismatch with print data', () {
    expect(() => InvoicePrintData.fromFinDoc(
      document: doc(),
      verifiedCurrencyId: 'USD',
      verifiedIdentity: identity(currency: 'YER'),
    ), throwsStateError);
  });

  test('P0: reject sales/purchase direction conflict', () {
    expect(() => identity(kind: VerifiedInvoiceKind.salesCash).validate(doc()),
      throwsStateError);
    expect(() => identity(kind: VerifiedInvoiceKind.purchaseCash)
      .validate(doc(sales: true)), throwsStateError);
  });

  test('P0: verified type overrides unsupported docSubType guess', () {
    final result = InvoicePrintData.fromFinDoc(
      document: doc(subtype: 'credit'),
      verifiedCurrencyId: 'YER',
      verifiedIdentity: identity(kind: VerifiedInvoiceKind.purchaseReturn),
    );
    expect(result.title, 'فاتورة مرتجع شراء');
  });

  test('P0: six verified types map to correct titles', () {
    final expected = <VerifiedInvoiceKind, String>{
      VerifiedInvoiceKind.salesCash: 'فاتورة بيع نقدي',
      VerifiedInvoiceKind.salesCredit: 'فاتورة بيع آجل',
      VerifiedInvoiceKind.purchaseCash: 'فاتورة شراء نقدي',
      VerifiedInvoiceKind.purchaseCredit: 'فاتورة شراء آجل',
      VerifiedInvoiceKind.salesReturn: 'فاتورة مرتجع بيع',
      VerifiedInvoiceKind.purchaseReturn: 'فاتورة مرتجع شراء',
    };
    for (final entry in expected.entries) {
      final verified = identity(kind: entry.key);
      expect(verified.title, entry.value);
      final isSales = entry.key.name.startsWith('sales');
      verified.validate(doc(sales: isSales));
    }
  });
}
