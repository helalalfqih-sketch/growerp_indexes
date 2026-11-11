import 'package:pdf/widgets.dart' as pw;

/// PDF display policy: preserve source strings and isolate machine-readable
/// values from Arabic Bidi reordering. Never reverse strings manually.
class InvoicePdfTextPolicy {
  const InvoicePdfTextPolicy._();

  /// Seven-column invoice: index, description, expiry, UOM, qty, price, total.
  static pw.TextDirection columnDirection(int index) {
    if (index < 0 || index > 6) {
      throw RangeError.range(index, 0, 6, 'index');
    }
    return switch (index) {
      1 || 3 => pw.TextDirection.rtl,
      _ => pw.TextDirection.ltr,
    };
  }

  static pw.TextDirection directionForPhone() => pw.TextDirection.ltr;
  static pw.TextDirection directionForDocumentId() => pw.TextDirection.ltr;
  static pw.TextDirection directionForDate() => pw.TextDirection.ltr;
  static pw.TextDirection directionForMoney() => pw.TextDirection.ltr;
  static pw.TextDirection directionForArabicLabel() => pw.TextDirection.rtl;
}
