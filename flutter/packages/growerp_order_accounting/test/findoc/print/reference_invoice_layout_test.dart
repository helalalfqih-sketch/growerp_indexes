import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_data.dart';
import 'package:growerp_order_accounting/src/findoc/print/nasqo_invoice_pdf_renderer.dart';
import 'package:pdf/pdf.dart';

Decimal amount(String value) => Decimal.parse(value);

void main() {
  test('reference purchase credit title and authoritative 63,000 total', () {
    final doc = FinDoc(
      docType: FinDocType.invoice,
      sales: false,
      docSubType: 'credit',
      pseudoId: '348',
      creationDate: DateTime.utc(2026, 11, 6),
      grandTotal: amount('63000'),
      items: [
        FinDocItem(quantity: amount('10'), price: amount('1500')),
        FinDocItem(quantity: amount('2'), price: amount('2500')),
        FinDocItem(quantity: amount('1'), price: amount('2000')),
        FinDocItem(quantity: amount('1'), price: amount('25000')),
        FinDocItem(quantity: amount('1'), price: amount('16000')),
      ],
    );
    final data = InvoicePrintData.fromFinDoc(
      document: doc, verifiedCurrencyId: 'YER');
    expect(data.title, 'فاتورة شراء آجل');
    expect(data.invoiceNumber, '348');
    expect(data.authoritativeTotal, amount('63000'));
    expect(data.lines.length, 5);
    final sum = data.lines.map((line) => line.calculatedTotal!)
        .reduce((a, b) => a + b);
    final qty = data.lines.map((line) => line.quantity!)
        .reduce((a, b) => a + b);
    expect(sum, amount('63000'));
    expect(qty, amount('15'));
    expect(data.warehouseName, isNull);
    expect(data.lines.every((line) => line.expiryDate == null), isTrue);
  });

  test('reference palette and document title', () {
    expect(NasqoInvoicePdfRenderer.blue, const PdfColor.fromInt(0xFF2953AF));
    expect(NasqoInvoicePdfRenderer.red, const PdfColor.fromInt(0xFFC62828));
    expect(NasqoInvoicePdfRenderer.yellow, const PdfColor.fromInt(0xFFFFFBC2));
  });
}
