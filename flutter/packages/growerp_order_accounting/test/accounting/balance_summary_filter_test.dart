import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/src/accounting/ledger/views/balance_summary_filter.dart';

void main() {
  final accounts = [
    GlAccount(accountCode: '200', accountName: 'الموردون'),
    GlAccount(accountCode: '100', accountName: 'العملاء'),
    GlAccount(accountCode: '120', accountName: 'Cash on Hand'),
  ];

  test('finds Arabic account names without modifying financial values', () {
    final result = filterBalanceSummaryAccounts(accounts, query: '  العملاء  ');
    expect(result.map((account) => account.accountCode), ['100']);
    expect(result.single.accountName, 'العملاء');
  });

  test('searches account codes and ignores case for Latin names', () {
    expect(
      filterBalanceSummaryAccounts(accounts, query: '120')
          .single.accountName,
      'Cash on Hand',
    );
    expect(
      filterBalanceSummaryAccounts(accounts, query: 'cAsH')
          .single.accountCode,
      '120',
    );
  });

  test('unmatched searches return an empty list, never an invalid index', () {
    expect(
      filterBalanceSummaryAccounts(accounts, query: 'غير موجود'),
      isEmpty,
    );
  });

  test('sorts displayed account codes in either direction', () {
    expect(
      filterBalanceSummaryAccounts(accounts)
          .map((account) => account.accountCode),
      ['100', '120', '200'],
    );
    expect(
      filterBalanceSummaryAccounts(accounts, ascending: false)
          .map((account) => account.accountCode),
      ['200', '120', '100'],
    );
  });

  test('sorting and filtering leave the backend report list unchanged', () {
    final source = [...accounts];
    final filtered = filterBalanceSummaryAccounts(
      accounts,
      query: '',
      ascending: false,
    );
    expect(accounts, orderedEquals(source));
    expect(identical(accounts, filtered), isFalse);
  });
  test('fiscal-year navigation handles annual, quarterly and monthly labels', () {
    expect(fiscalYearFromBalancePeriod('Y2025'), 2025);
    expect(fiscalYearFromBalancePeriod('Y2025q1'), 2025);
    expect(fiscalYearFromBalancePeriod('Y2025q4'), 2025);
    expect(fiscalYearFromBalancePeriod('Y2025m01'), 2025);
    expect(fiscalYearFromBalancePeriod('Y2025m12'), 2025);
  });

  test('invalid accounting period names never fall back to today', () {
    expect(fiscalYearFromBalancePeriod('Y2025q9'), isNull);
    expect(fiscalYearFromBalancePeriod('Y2025m13'), isNull);
    expect(fiscalYearFromBalancePeriod('bad'), isNull);
  });

}
