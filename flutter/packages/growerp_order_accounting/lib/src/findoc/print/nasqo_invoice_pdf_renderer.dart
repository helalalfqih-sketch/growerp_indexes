import 'dart:typed_data';

import 'package:flutter/services.dart' show rootBundle;
import 'package:growerp_models/growerp_models.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

import 'invoice_pdf_text_policy.dart';
import 'invoice_print_data.dart';
import 'invoice_print_enrichment.dart';

/// Opt-in Arabic A4 invoice renderer. No accounting writes.
class NasqoInvoicePdfRenderer {
  static const blue = PdfColor.fromInt(0xFF2953AF);
  static const red = PdfColor.fromInt(0xFFC62828);
  static const yellow = PdfColor.fromInt(0xFFFFFBC2);
  static const borderColor = PdfColor.fromInt(0xFF444444);

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
    String? verifiedCompanyEnglishName,
    String? verifiedAmountInWords,
    InvoicePrintEnrichment? enrichment,
  }) async {
    final printData = InvoicePrintData.fromFinDoc(
      document: finDoc,
      verifiedCurrencyId: currencyId,
      verifiedWarehouseName: verifiedWarehouseName,
      enrichment: enrichment,
    );
    final fontData = await rootBundle.load(
      'packages/growerp_order_accounting/assets/fonts/DejaVuSans.ttf');
    final font = pw.Font.ttf(fontData);
    // Unicode font + pdf's Bidi shaping; no manual string reversal.
    final pdf = pw.Document(
      title: printData.title,
      author: company.name ?? 'NASQO',
    );
    final theme = pw.ThemeData.withFont(base: font, bold: font);
    final number = printData.invoiceNumber;
    final date = printData.createdAt.toIso8601String().split('T').first;
    final time = printData.createdAt.toIso8601String().split('T').last.split('.').first;
    final party = finDoc.otherCompany?.name ?? finDoc.otherUser?.name ?? '—';
    final warehouse = printData.warehouseName;
    final companyAddress = [
      company.address?.address1, company.address?.address2,
      company.address?.city, company.address?.country,
    ].whereType<String>().where((part) => part.trim().isNotEmpty).join('، ');
    // Summing different UOMs is misleading: show the quantity total only
    // when every line has a verified identical sale unit.
    final knownQuantity = printData.lines.isNotEmpty &&
        printData.lines.every((line) =>
            line.quantity != null && line.unitName?.trim().isNotEmpty == true) &&
        printData.lines.every((line) =>
            line.unitName == printData.lines.first.unitName);
    final quantityTotal = knownQuantity
        ? printData.lines.map((line) => line.quantity!).reduce((a, b) => a + b)
        : null;
    // TableHelper.fromTextArray produces a spanning Table; headerCount=1
    // marks its first row repeatable on every continuation page.
    final rows = printData.lines.asMap().entries.map((e) {
      final item = e.value;
      final total = item.calculatedTotal?.toString() ?? '—';
      return <String>[
        (e.key + 1).toString(),
        item.description,
        item.expiryDate?.toIso8601String().split('T').first ?? '',
        item.unitName ?? '',
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
          pw.Expanded(child: pw.Column(
            crossAxisAlignment: pw.CrossAxisAlignment.start,
            children: [
              pw.Text(company.name ?? 'NASQO',
                style: pw.TextStyle(fontSize: 14, fontWeight: pw.FontWeight.bold)),
              if (companyAddress.isNotEmpty)
                pw.Text(companyAddress, style: const pw.TextStyle(fontSize: 8)),
            ],
          )),
          if (company.image != null)
            pw.SizedBox(width: 54, height: 54,
              child: pw.Image(pw.MemoryImage(company.image!))),
          pw.Expanded(child: pw.Column(
            crossAxisAlignment: pw.CrossAxisAlignment.end,
            children: [
              if (verifiedCompanyEnglishName?.trim().isNotEmpty == true)
                pw.Text(verifiedCompanyEnglishName!,
                  textDirection: pw.TextDirection.ltr),
              if (company.telephoneNr?.isNotEmpty == true)
                pw.Text(company.telephoneNr!,
                  textDirection: InvoicePdfTextPolicy.directionForPhone()),
              if (company.email?.isNotEmpty == true)
                pw.Text(company.email!, textDirection: pw.TextDirection.ltr),
            ],
          )),
        ]),
        pw.SizedBox(height: 5),
        pw.Divider(color: borderColor, thickness: 0.6),
        pw.SizedBox(height: 5),
        pw.Container(color: blue,
          padding: const pw.EdgeInsets.all(7),
          child: pw.Center(child: pw.Text(printData.title,
            style: pw.TextStyle(color: PdfColors.white, fontSize: 14,
              fontWeight: pw.FontWeight.bold)))),
        pw.SizedBox(height: 8),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween, children: [
          pw.Row(mainAxisSize: pw.MainAxisSize.min, children: [
            pw.Text('التاريخ: '),
            pw.Text(date + '  ' + time,
              textDirection: InvoicePdfTextPolicy.directionForDate()),
          ]),
          pw.Row(mainAxisSize: pw.MainAxisSize.min, children: [
            pw.Text('رقم الفاتورة: ',
              style: pw.TextStyle(color: red, fontWeight: pw.FontWeight.bold)),
            pw.Text(number,
              textDirection: InvoicePdfTextPolicy.directionForDocumentId(),
              style: pw.TextStyle(color: red, fontWeight: pw.FontWeight.bold)),
          ]),
        ]),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween, children: [
          pw.Text((finDoc.sales ? 'العميل: ' : 'المورد: ') + party),
          if (warehouse != null) pw.Text('المخزن: $warehouse'),
        ]),
        if (finDoc.description?.isNotEmpty == true)
          pw.Text('ملاحظات: ' + finDoc.description!),
        pw.Divider(color: borderColor, thickness: 0.6),
        pw.SizedBox(height: 5),
      ]),
      build: (context) => [
        pw.TableHelper.fromTextArray(
          context: context,
          tableDirection: pw.TextDirection.rtl,
          headerDirection: pw.TextDirection.rtl,
          headerCount: 1, // Repeat blue column headings after automatic page breaks.
          headers: const ['م', 'اسم الصنف', 'ت الانتهاء', 'الوحدة', 'العدد', 'السعر', 'الإجمالي'],
          data: rows,
          headerStyle: pw.TextStyle(color: PdfColors.white,
            fontWeight: pw.FontWeight.bold, fontSize: 9),
          headerDecoration: const pw.BoxDecoration(color: blue),
          border: pw.TableBorder.all(color: borderColor, width: 0.5),
          cellStyle: const pw.TextStyle(fontSize: 9),
          cellPadding: const pw.EdgeInsets.symmetric(vertical: 5, horizontal: 3),
          // Bidi Arabic descriptions stay RTL; IDs/dates/quantities/prices LTR.
          cellBuilder: (index, value, row) => pw.Text(
            value.toString(),
            textDirection: InvoicePdfTextPolicy.columnDirection(index),
            style: pw.TextStyle(fontSize: 9,
              color: index == 1 || index == 3 ? borderColor : blue),
          ),
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
        pw.SizedBox(height: 5),
        if (quantityTotal != null)
          pw.Container(padding: const pw.EdgeInsets.symmetric(vertical: 4, horizontal: 8),
            child: pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
              children: [pw.Text('إجمالي العدد'),
                pw.Text(quantityTotal.toString(), textDirection: pw.TextDirection.ltr)])),
        pw.SizedBox(height: 5),
        pw.Container(color: yellow, padding: const pw.EdgeInsets.all(8),
          child: pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
            children: [
              pw.Text('صافي الفاتورة'),
              pw.Text(printData.authoritativeTotal.toString() + ' ' + printData.currencyId,
                textDirection: InvoicePdfTextPolicy.directionForMoney(),
                style: pw.TextStyle(color: red, fontWeight: pw.FontWeight.bold)),
            ])),
        if (printData.paidTotal != null && printData.remainingTotal != null) ...[
          pw.SizedBox(height: 5),
          pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
            children: [
              pw.Text('المدفوع'),
              pw.Text(printData.paidTotal.toString() + ' ' + printData.currencyId,
                textDirection: InvoicePdfTextPolicy.directionForMoney()),
            ]),
          pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
            children: [
              pw.Text('المتبقي'),
              pw.Text(printData.remainingTotal.toString() + ' ' + printData.currencyId,
                textDirection: InvoicePdfTextPolicy.directionForMoney()),
            ]),
        ],
        if (verifiedAmountInWords?.trim().isNotEmpty == true) ...[
          pw.SizedBox(height: 6),
          pw.Text(verifiedAmountInWords!,
            style: pw.TextStyle(color: red, fontSize: 10,
              fontWeight: pw.FontWeight.bold)),
        ],
        if (returnPolicy?.trim().isNotEmpty == true) ...[
          pw.SizedBox(height: 12), pw.Center(child: pw.Text(returnPolicy!)),
        ],
      ],
      footer: (context) => pw.Column(children: [
        pw.Divider(color: PdfColors.grey),
        pw.Row(mainAxisAlignment: pw.MainAxisAlignment.spaceAround,
          children: ['البائع', 'المخازن', 'الصندوق', 'المستلم']
            .map((s) => pw.Text(s, style: const pw.TextStyle(fontSize: 9))).toList()),
        pw.Align(alignment: pw.Alignment.centerLeft,
          child: pw.Text('صفحة ${context.pageNumber} من ${context.pagesCount}',
            style: const pw.TextStyle(fontSize: 8))),
      ]),
    ));
    return pdf.save();
  }
}
