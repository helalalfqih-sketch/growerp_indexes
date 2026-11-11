import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_data.dart';
import 'package:growerp_order_accounting/src/findoc/print/invoice_print_enrichment.dart';

Decimal dec(String n) => Decimal.parse(n);
FinDoc sampleInvoice() => FinDoc(
  docType: FinDocType.invoice,
  invoiceId: 'REAL-INV-42',
  pseudoId: '348',
  creationDate: DateTime.utc(2026, 11, 6),
  grandTotal: dec('63000'),
  items: [FinDocItem(itemSeqId: '00001', description: 'دواء',
    quantity: dec('10'), price: dec('1500'))],
);
InvoicePrintData convert(InvoicePrintEnrichment extra) =>
    InvoicePrintData.fromFinDoc(
      document: sampleInvoice(),
      verifiedCurrencyId: 'YER',
      enrichment: extra,
    );

void main() {
  test('enrichment refuses mismatched backend invoice ID', () {
    expect(() => convert(const InvoicePrintEnrichment(invoiceId: 'WRONG')),
      throwsStateError);
  });

  test('verified warehouse requires facility ID', () {
    expect(() => convert(const InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42', warehouseName: 'المخزن الرئيسي')),
      throwsStateError);
    expect(convert(const InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42', facilityId: 'FAC-1',
      warehouseName: 'المخزن الرئيسي')).warehouseName, 'المخزن الرئيسي');
  });

  test('expiry must be tied to verified allocated asset/lot', () {
    expect(() => convert(InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42',
      linesByItemSeqId: {'00001': VerifiedInvoiceLinePrintData(
        expiryDate: DateTime.utc(2027, 6, 1))})), throwsStateError);
    final data = convert(InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42',
      linesByItemSeqId: {'00001': VerifiedInvoiceLinePrintData(
        verifiedLotAssetId: 'ASSET-1',
        expiryDate: DateTime.utc(2027, 6, 1))}));
    expect(data.lines.single.expiryDate, DateTime.utc(2027, 6, 1));
  });

  test('sale unit must have both ID and display name', () {
    expect(() => convert(const InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42',
      linesByItemSeqId: {'00001': VerifiedInvoiceLinePrintData(
        saleUnitName: 'حبة')})), throwsStateError);
    final data = convert(const InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42',
      linesByItemSeqId: {'00001': VerifiedInvoiceLinePrintData(
        saleUomId: 'OTH_ea', saleUnitName: 'حبة')}));
    expect(data.lines.single.unitName, 'حبة');
  });

  test('unmatched line enrichment is rejected', () {
    expect(() => convert(const InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42',
      linesByItemSeqId: {'OTHER': VerifiedInvoiceLinePrintData(
        saleUomId: 'OTH_ea', saleUnitName: 'حبة')})), throwsStateError);
  });

  test('payment must have evidence and reconcile to grandTotal', () {
    expect(() => convert(InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42', paidTotal: dec('20000'),
      remainingTotal: dec('43000'))), throwsStateError);
    expect(() => convert(InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42', paymentEvidenceId: 'APPSET-1',
      paidTotal: dec('20000'), remainingTotal: dec('41000'))),
      throwsStateError);
    final data = convert(InvoicePrintEnrichment(
      invoiceId: 'REAL-INV-42', paymentEvidenceId: 'APPSET-1',
      paidTotal: dec('20000'), remainingTotal: dec('43000')));
    expect(data.paidTotal, dec('20000'));
    expect(data.remainingTotal, dec('43000'));
  });

  test('no enrichment never invents financial or inventory fields', () {
    final data = InvoicePrintData.fromFinDoc(
      document: sampleInvoice(), verifiedCurrencyId: 'YER');
    expect(data.warehouseName, isNull);
    expect(data.paidTotal, isNull);
    expect(data.remainingTotal, isNull);
    expect(data.lines.single.unitName, isNull);
    expect(data.lines.single.expiryDate, isNull);
  });
}
