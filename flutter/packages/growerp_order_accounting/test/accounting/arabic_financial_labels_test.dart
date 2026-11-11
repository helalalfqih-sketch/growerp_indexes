import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_order_accounting/growerp_order_accounting.dart';

void main() {
  test('Arabic accounting periods distinguish quarters from quantities', () async {
    final ar = await OrderAccountingLocalizations.delegate.load(
      const Locale('ar'),
    );

    // These markers are displayed beside year/quarter/month period values.
    // A previous translation incorrectly rendered Q (quarter) as quantity.
    expect(ar.yearLetter, 'سنة');
    expect(ar.quarterLetter, 'ر');
    expect(ar.monthLetter, 'شهر');
    expect(ar.quantity, 'الكمية');
    expect(ar.quarterLetter, isNot(ar.quantity));

    // Financial transaction signs must not be reversed by localization.
    expect(ar.debit, 'مدين');
    expect(ar.credit, 'دائن');
  });
}
