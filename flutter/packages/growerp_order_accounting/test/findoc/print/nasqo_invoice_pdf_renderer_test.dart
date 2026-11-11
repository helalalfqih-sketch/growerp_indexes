import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/nasqo_invoice_pdf_renderer.dart';

void main() {
  group('NASQO Arabic invoice: document titles', () {
    test('sales credit', () {
      expect(NasqoInvoicePdfRenderer.title(
        FinDoc(docType: FinDocType.invoice, sales: true, docSubType: 'credit')),
        'فاتورة بيع آجل');
    });
    test('purchase cash', () {
      expect(NasqoInvoicePdfRenderer.title(
        FinDoc(docType: FinDocType.invoice, sales: false, docSubType: 'cash')),
        'فاتورة شراء نقدي');
    });
    test('sales return', () {
      expect(NasqoInvoicePdfRenderer.title(
        FinDoc(docType: FinDocType.invoice, sales: true, docSubType: 'return')),
        'فاتورة مرتجع بيع');
    });
    test('purchase return', () {
      expect(NasqoInvoicePdfRenderer.title(
        FinDoc(docType: FinDocType.invoice, sales: false, docSubType: 'return')),
        'فاتورة مرتجع شراء');
    });
  });
  group('NASQO Arabic invoice: no invented warehouse', () {
    test('missing warehouse remains missing', () {
      expect(NasqoInvoicePdfRenderer.verifiedWarehouse(null), isNull);
      expect(NasqoInvoicePdfRenderer.verifiedWarehouse('   '), isNull);
    });
    test('verified warehouse is retained', () {
      expect(NasqoInvoicePdfRenderer.verifiedWarehouse(' المخزن الرئيسي '),
        'المخزن الرئيسي');
    });
  });
}
