import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_pdf_text_policy.dart';
import 'package:pdf/widgets.dart' as pw;

void main() {
  group('INV-06/07: explicit mixed-script PDF direction', () {
    test('invoice number and +967 phone must remain LTR', () {
      expect(InvoicePdfTextPolicy.directionForDocumentId(), pw.TextDirection.ltr);
      expect(InvoicePdfTextPolicy.directionForPhone(), pw.TextDirection.ltr);
      expect(InvoicePdfTextPolicy.directionForDate(), pw.TextDirection.ltr);
      expect(InvoicePdfTextPolicy.directionForMoney(), pw.TextDirection.ltr);
    });

    test('Arabic labels, item names and unit names remain RTL', () {
      expect(InvoicePdfTextPolicy.directionForArabicLabel(), pw.TextDirection.rtl);
      expect(InvoicePdfTextPolicy.columnDirection(1), pw.TextDirection.rtl);
      expect(InvoicePdfTextPolicy.columnDirection(3), pw.TextDirection.rtl);
    });

    test('numeric and expiry columns use LTR', () {
      for (final index in [0, 2, 4, 5, 6]) {
        expect(InvoicePdfTextPolicy.columnDirection(index), pw.TextDirection.ltr);
      }
    });

    test('invalid table column indices are rejected', () {
      expect(() => InvoicePdfTextPolicy.columnDirection(-1), throwsRangeError);
      expect(() => InvoicePdfTextPolicy.columnDirection(7), throwsRangeError);
    });
  });
}
