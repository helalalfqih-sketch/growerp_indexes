import 'dart:typed_data';
import 'package:flutter/services.dart' show rootBundle;
import 'package:growerp_models/growerp_models.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

/// Opt-in Arabic A4 invoice renderer. No accounting writes.
class NasqoInvoicePdfRenderer {
  static const blue = PdfColor.fromInt(0xFF2953AF);
  static const red = PdfColor.fromInt(0xFFC62828);
  static const yellow = PdfColor.fromInt(0xFFFFFBC2);

  static String title(FinDoc doc) {
    final kind = doc.sales ? 'بيع' : 'شراء';
    final sub = (doc.docSubType ?? '').toLowerCase();
    if (sub.contains('return') || sub.contains('مرتجع')) return 'فاتورة مرتجع $kind';
    final payment = sub.contains('cash') || sub.contains('نقد') ? 'نقدي' :
      sub.contains('credit') || sub.contains('آجل') ? 'آجل' : '';
    return 'فاتورة $kind $payment'.trim();
  }

  static String? verifiedWarehouse(String? name) =>
    name?.trim().isNotEmpty == true ? name!.trim() : null;

  static Future<Uint8List> render({
    required PdfPageFormat format,
    required Company company,
    required FinDoc finDoc,
    required String currencyId,
    String? verifiedWarehouseName,
    String? returnPolicy,
  }) async {
    if (finDoc.docType != FinDocType.invoice) {
      throw ArgumentError('Only invoice FinDoc is supported');
    }
    if (finDoc.grandTotal == null) throw StateError('Authoritative grandTotal is required');
    if (currencyId.trim().isEmpty) throw StateError('Currency is required');
    final fontData = await rootBundle.load(
      'packages/growerp_order_accounting/assets/fonts/DejaVuSans.ttf');
    final font = pw.Font.ttf(fontData);
    final pdf = pw.Document();
    final theme = pw.ThemeData.withFont(base: font, bold: font);
    final number = finDoc.pseudoId ?? finDoc.invoiceId ?? '—';
    final date = finDoc.creationDate?.toIso8601String().split('T').first ?? '—';
    final party = finDoc.otherCompany?.name ?? finDoc.otherUser?.name ?? '—';
    final warehouse = verifiedWarehouse(verifiedWarehouseName);
    final rows = finDoc.items.asMap().entries.map((e) {
      final item = e.value;
      final total = item.quantity != null && item.price != null
        ? (item.quantity! * item.price!).toString() : '—';
      return <String>[
        (e.key + 1).toString(),
        item.description ?? item.product?.productName ?? '—',
        '', // Expiry requires a verified inventory lot; never fabricated.
        item.product?.amountUom?.abbreviation ?? '',
        item.quantity?.toString() ?? '—',
        item.price?.toString() ?? '—',
        total,
      ];
    }).toList();
    pdf.addPage(pw.MultiPage(
      pageFormat: format,
      theme: theme,
      textDirection: pw.TextDirection.rtl,
      margin: const pw.EdgeInsets.all(24),
      header: (context) => pw.Column(children: [
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween, children: [
          pw.Expanded(child: pw.Text(company.name ?? 'NASQO',
            style: pw.TextStyle(fontSize: 14, fontWeight: pw.FontWeight.bold))),
          if (company.image != null)
            pw.SizedBox(width: 54, height: 54,
              child: pw.Image(pw.MemoryImage(company.image!))),
          pw.Expanded(child: pw.Column(
            crossAxisAlignment: pw.CrossAxisAlignment.end,
            children: [
              pw.Text(company.telephoneNr ?? '', textDirection: pw.TextDirection.ltr),
              pw.Text(company.email ?? '', textDirection: pw.TextDirection.ltr),
            ],
          )),
        ]),
        pw.SizedBox(height: 8),
        pw.Container(color: blue,
          padding: const pw.EdgeInsets.all(7),
          child: pw.Center(child: pw.Text(title(finDoc),
            style: pw.TextStyle(color: PdfColors.white, fontSize: 14,
              fontWeight: pw.FontWeight.bold)))),
        pw.SizedBox(height: 8),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween, children: [
          pw.Text('التاريخ: $date'),
          pw.Text('رقم الفاتورة: $number',
            style: pw.TextStyle(color: red, fontWeight: pw.FontWeight.bold)),
        ]),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween, children: [
          pw.Text((finDoc.sales ? 'العميل: ' : 'المورد: ') + party),
          if (warehouse != null) pw.Text('المخزن: $warehouse'),
        ]),
        if (finDoc.description?.isNotEmpty == true)
          pw.Text('ملاحظات: ' + finDoc.description!),
        pw.SizedBox(height: 8),
      ]),
      build: (context) => [
        pw.TableHelper.fromTextArray(
          headers: const ['م', 'اسم الصنف', 'ت الانتهاء', 'الوحدة', 'العدد', 'السعر', 'الإجمالي'],
          data: rows,
          headerStyle: pw.TextStyle(color: PdfColors.white,
            fontWeight: pw.FontWeight.bold, fontSize: 9),
          headerDecoration: const pw.BoxDecoration(color: blue),
          cellStyle: const pw.TextStyle(fontSize: 9),
          cellAlignments: {
            0: pw.Alignment.center,
            1: pw.Alignment.centerRight,
            2: pw.Alignment.center,
            3: pw.Alignment.center,
            4: pw.Alignment.center,
            5: pw.Alignment.center,
            6: pw.Alignment.center,
          },
          columnWidths: {
            0: const pw.FlexColumnWidth(7),
            1: const pw.FlexColumnWidth(34),
            2: const pw.FlexColumnWidth(12),
            3: const pw.FlexColumnWidth(11),
            4: const pw.FlexColumnWidth(11),
            5: const pw.FlexColumnWidth(12),
            6: const pw.FlexColumnWidth(13),
          },
        ),
        pw.SizedBox(height: 8),
        pw.Container(color: yellow, padding: const pw.EdgeInsets.all(8),
          child: pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
            children: [
              pw.Text('صافي الفاتورة'),
              pw.Text(finDoc.grandTotal.toString() + ' ' + currencyId,
                textDirection: pw.TextDirection.ltr,
                style: pw.TextStyle(color: red, fontWeight: pw.FontWeight.bold)),
            ])),
        if (returnPolicy?.trim().isNotEmpty == true) ...[
          pw.SizedBox(height: 12), pw.Center(child: pw.Text(returnPolicy!)),
        ],
      ],
      footer: (context) => pw.Column(children: [
        pw.Divider(color: PdfColors.grey),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceAround,
          children: ['البائع', 'المخازن', 'الصندوق', 'المستلم']
            .map((s) => pw.Text(s, style: const pw.TextStyle(fontSize: 9))).toList()),
      ]),
    ));
    return pdf.save();
  }
}
