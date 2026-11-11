import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_data.dart';
import 'package:growerp_order_accounting/src/findoc/print/nasqo_invoice_pdf_renderer.dart';

Decimal d(String n) => Decimal.parse(n);
FinDoc invoice({
  bool sales = false,
  String subtype = 'credit',
  Decimal? total,
  DateTime? date,
  List<FinDocItem> items = const [],
}) => FinDoc(
  docType: FinDocType.invoice,
  sales: sales,
  docSubType: subtype,
  pseudoId: 'INV-2026-0348',
  creationDate: date ?? DateTime.utc(2026, 11, 6),
  grandTotal: total ?? d('63000'),
  items: items,
);

InvoicePrintData map(FinDoc doc, {
  String currency = 'YER',
  String? warehouse,
  Decimal? paid,
  Decimal? remaining,
}) => InvoicePrintData.fromFinDoc(
  document: doc,
  verifiedCurrencyId: currency,
  verifiedWarehouseName: warehouse,
  verifiedPaidTotal: paid,
  verifiedRemainingTotal: remaining,
);

void main() {
  test('INV-01 identity, date and title preserved', () {
    final result = map(invoice());
    expect(result.invoiceNumber, 'INV-2026-0348');
    expect(result.createdAt, DateTime.utc(2026, 11, 6));
    expect(result.title, 'فاتورة شراء آجل');
  });

  test('INV-02 never invent warehouse, lot expiry or sale UOM', () {
    final result = map(invoice(items: [
      FinDocItem(description: 'دواء', quantity: d('2'), price: d('10'),
        product: Product(productName: 'دواء', amountUom: Uom(abbreviation: 'حبة'))),
    ]));
    expect(result.warehouseName, isNull);
    expect(result.lines.single.expiryDate, isNull);
    expect(result.lines.single.unitName, isNull);
    expect(map(invoice(), warehouse: 'المخزن الرئيسي').warehouseName, 'المخزن الرئيسي');
  });

  test('INV-03 authoritative total is not recomputed from lines', () {
    final result = map(invoice(total: d('63000'), items: [
      FinDocItem(quantity: d('1'), price: d('5')),
    ]));
    expect(result.authoritativeTotal, d('63000'));
    expect(result.lines.single.calculatedTotal, d('5'));
  });

  test('INV-04 paid + remaining must reconcile exactly', () {
    final doc = invoice(total: d('61500'));
    expect(map(doc, paid: d('20000'), remaining: d('41500')).remainingTotal, d('41500'));
    expect(() => map(doc, paid: d('20000'), remaining: d('61500')), throwsStateError);
    expect(() => map(doc, paid: d('20000')), throwsStateError);
  });

  test('INV-05 amount-to-words contract pending implementation', () {
    // Deliberately pending: do not claim Arabic currency words are verified.
    expect(map(invoice()).currencyId, 'YER');
  }, skip: 'Arabic amount-to-words implementation and currency fraction tests pending');

  test('INV-06 invoice number retains original LTR characters', () {
    expect(map(invoice()).invoiceNumber, 'INV-2026-0348');
  });

  test('INV-07 mixed Arabic/English product text remains unmodified', () {
    const name = 'مكينة Brother XL-2026';
    final result = map(invoice(items: [FinDocItem(description: name)]));
    expect(result.lines.single.description, name);
  });

  test('INV-08 embedded Arabic font and blue PDF header: rendering gate', () {
    expect(NasqoInvoicePdfRenderer.blue, isNotNull);
  }, skip: 'Requires generated PDF inspection and Arabic shaping visual approval');

  test('INV-09 45 item lines preserved before PDF pagination', () {
    final result = map(invoice(items: List.generate(45,
      (i) => FinDocItem(description: 'صنف رقم $i', quantity: d('1'), price: d('2')))));
    expect(result.lines, hasLength(45));
  });

  test('INV-10 A4 printable bounds: PDF output gate', () {
    expect(map(invoice()).invoiceNumber, isNotEmpty);
  }, skip: 'Requires PDF output page geometry and clipping inspection');

  test('INV-11 sales/purchase/cash/credit/returns title matrix', () {
    expect(map(invoice(sales: true, subtype: 'cash')).title, 'فاتورة بيع نقدي');
    expect(map(invoice(sales: true, subtype: 'credit')).title, 'فاتورة بيع آجل');
    expect(map(invoice(sales: false, subtype: 'cash')).title, 'فاتورة شراء نقدي');
    expect(map(invoice(sales: false, subtype: 'credit')).title, 'فاتورة شراء آجل');
    expect(map(invoice(sales: true, subtype: 'return')).title, 'فاتورة مرتجع بيع');
    expect(map(invoice(sales: false, subtype: 'return')).title, 'فاتورة مرتجع شراء');
  });

  test('INV-12 legacy selection gate', () {
    expect(map(invoice()).authoritativeTotal, d('63000'));
  }, skip: 'Requires integration test with PdfFormats legacy/Arabic feature flag');
}
