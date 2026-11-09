/// Read-only preview returned by the legacy SQLite backup importer.
class SqliteImportPreview {
  final String? format;
  final bool readOnly;
  final Map<String, int> counts;
  final List<Map<String, dynamic>> billTypes;
  final List<Map<String, dynamic>> currencies;
  final List<Map<String, dynamic>> stores;
  final List<String> warnings;
  /// Accounting-only import readiness, never a posting authorization.
  final Map<String, dynamic> accountingReview;

  const SqliteImportPreview({
    this.format,
    this.readOnly = true,
    this.counts = const {},
    this.billTypes = const [],
    this.currencies = const [],
    this.stores = const [],
    this.warnings = const [],
    this.accountingReview = const {},
  });

  factory SqliteImportPreview.fromJson(Map<String, dynamic> json) {
    final value = json['preview'] is Map<String, dynamic>
        ? json['preview'] as Map<String, dynamic>
        : json;
    return SqliteImportPreview(
      format: value['format'] as String?,
      readOnly: value['readOnly'] as bool? ?? true,
      counts: (value['counts'] as Map<String, dynamic>? ?? {}).map(
        (key, item) => MapEntry(key, (item as num).toInt()),
      ),
      billTypes: _maps(value['billTypes']),
      currencies: _maps(value['currencies']),
      stores: _maps(value['stores']),
      warnings: (value['warnings'] as List<dynamic>? ?? [])
          .map((item) => item.toString())
          .toList(),
      accountingReview: value['accountingReview'] is Map<String, dynamic>
          ? value['accountingReview'] as Map<String, dynamic>
          : const {},
    );
  }

  static List<Map<String, dynamic>> _maps(Object? value) =>
      (value as List<dynamic>? ?? [])
          .whereType<Map<String, dynamic>>()
          .toList();
}
