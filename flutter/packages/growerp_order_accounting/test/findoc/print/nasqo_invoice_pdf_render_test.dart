import 'dart:io';
import 'dart:convert';

import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/findoc/print/nasqo_invoice_pdf_renderer.dart';
import 'package:pdf/pdf.dart';

FinDoc sample({int count = 3}) => FinDoc(
  docType: FinDocType.invoice,
  sales: false,
  docSubType: 'credit',
  pseudoId: 'INV-2026-0348',
  creationDate: DateTime.utc(2026, 11, 6),
  grandTotal: Decimal.parse('63000'),
  otherCompany: Company(name: 'مورد Brother للصناعات'),
  items: List.generate(count, (i) => FinDocItem(
    description: 'مكينة خياطة Brother XL-2026 رقم $i',
    quantity: Decimal.parse('2'),
    price: Decimal.parse('1000'),
  )),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('INV-08: Arabic PDF bytes are generated with embedded font', () async {
    final bytes = await NasqoInvoicePdfRenderer.render(
      format: PdfPageFormat.a4,
      company: Company(name: 'مؤسسة نسقو', telephoneNr: '+967771370740'),
      finDoc: sample(),
      currencyId: 'YER',
    );
    expect(bytes.length, greaterThan(1000));
    expect(ascii.decode(bytes.take(5).toList()), '%PDF-');
    // Font embedding, glyph shaping and header color still require PDF inspection.
  });

  test('INV-09: 45 lines can render without a page-overflow exception', () async {
    final bytes = await NasqoInvoicePdfRenderer.render(
      format: PdfPageFormat.a4,
      company: Company(name: 'مؤسسة نسقو'),
      finDoc: sample(count: 45),
      currencyId: 'YER',
    );
    expect(bytes.length, greaterThan(1000));
    final artifactDir = Platform.environment['NASQO_PDF_ARTIFACT_DIR'];
    if (artifactDir != null && artifactDir.isNotEmpty) {
      final directory = Directory(artifactDir);
      await directory.create(recursive: true);
      await File('$artifactDir/nasqo-45-items-a4.pdf').writeAsBytes(bytes);
    }
    // This does not establish page count or repeated header: inspect output.
  });

  test('INV-10: renderer rejects unsupported non-invoice documents', () async {
    final doc = sample().copyWith(docType: FinDocType.order);
    await expectLater(
      NasqoInvoicePdfRenderer.render(
        format: PdfPageFormat.a4,
        company: Company(name: 'مؤسسة نسقو'),
        finDoc: doc,
        currencyId: 'YER',
      ),
      throwsArgumentError,
    );
  });

  test('INV-03: renderer rejects missing authoritative total', () async {
    final doc = sample().copyWith(grandTotal: null);
    await expectLater(
      NasqoInvoicePdfRenderer.render(
        format: PdfPageFormat.a4,
        company: Company(name: 'مؤسسة نسقو'),
        finDoc: doc,
        currencyId: 'YER',
      ),
      throwsStateError,
    );
  });

  test('INV-03: renderer rejects missing verified currency', () async {
    await expectLater(
      NasqoInvoicePdfRenderer.render(
        format: PdfPageFormat.a4,
        company: Company(name: 'مؤسسة نسقو'),
        finDoc: sample(),
        currencyId: '',
      ),
      throwsStateError,
    );
  });
}
