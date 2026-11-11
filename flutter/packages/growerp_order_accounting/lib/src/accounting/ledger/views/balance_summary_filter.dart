import 'package:growerp_models/growerp_models.dart';

/// Builds a display-only view of a balance summary. Filtering and sorting
/// never mutate the ledger report or recompute posted balances.
///
/// The backend still owns the financial data, report period and totals;
/// account code/name are the only supported client-side search fields.
List<GlAccount> filterBalanceSummaryAccounts(
  List<GlAccount> accounts, {
  String query = '',
  bool ascending = true,
}) {
  final needle = query.trim().toLowerCase();
  final filtered = accounts.where((account) {
    if (needle.isEmpty) return true;
    return (account.accountCode ?? '').toLowerCase().contains(needle) ||
        (account.accountName ?? '').toLowerCase().contains(needle);
  }).toList();

  filtered.sort((a, b) {
    final code = (a.accountCode ?? '').compareTo(b.accountCode ?? '');
    if (code != 0) return ascending ? code : -code;
    final name = (a.accountName ?? '').compareTo(b.accountName ?? '');
    return ascending ? name : -name;
  });
  return filtered;
}
