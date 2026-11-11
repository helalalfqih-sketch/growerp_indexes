import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/src/domains/common/widgets/styled_data_table.dart';

void main() {
  for (final direction in TextDirection.values) {
    testWidgets('table aligns start and end cells in $direction', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Directionality(
            textDirection: direction,
            child: const Scaffold(
              body: StyledDataTable(
                columns: [
                  StyledColumn(header: 'Name'),
                  StyledColumn(header: 'Amount', alignment: TextAlign.end),
                  StyledColumn(header: 'Code', alignment: TextAlign.left),
                ],
                rows: [
                  [
                    SizedBox(key: Key('name'), width: 24, height: 16),
                    SizedBox(key: Key('amount'), width: 24, height: 16),
                    SizedBox(key: Key('code'), width: 24, height: 16),
                  ],
                ],
              ),
            ),
          ),
        ),
      );

      void expectCellEdge(String key, {required bool right}) {
        final cell = find.byKey(Key(key));
        final alignment = find.ancestor(of: cell, matching: find.byType(Align));
        final cellRect = tester.getRect(cell);
        final columnRect = tester.getRect(alignment.first);
        expect(
          right ? cellRect.right : cellRect.left,
          closeTo(right ? columnRect.right : columnRect.left, 0.01),
        );
      }

      expectCellEdge('name', right: direction == TextDirection.rtl);
      expectCellEdge('amount', right: direction == TextDirection.ltr);
      // Explicit physical alignment must keep its original meaning.
      expectCellEdge('code', right: false);
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets('filtered empty state explains no results and clears the query',
      (tester) async {
    var clearCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: Scaffold(
            body: StyledDataTable(
              columns: const [StyledColumn(header: 'الحساب')],
              rows: const [],
              emptyMessage: 'لا توجد نتائج مطابقة للبحث',
              onClearFilter: () => clearCount++,
              clearFilterLabel: 'مسح البحث',
            ),
          ),
        ),
      ),
    );

    expect(find.text('لا توجد نتائج مطابقة للبحث'), findsOneWidget);
    expect(find.byKey(const Key('clearFilterEmptyState')), findsOneWidget);
    await tester.tap(find.byKey(const Key('clearFilterEmptyState')));
    expect(clearCount, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('explicit empty state does not suggest clearing a filter',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StyledDataTable(
            columns: [StyledColumn(header: 'الحساب')],
            rows: [],
            emptyMessage: 'لا توجد أرصدة في هذه الفترة',
          ),
        ),
      ),
    );

    expect(find.text('لا توجد أرصدة في هذه الفترة'), findsOneWidget);
    expect(find.byKey(const Key('clearFilterEmptyState')), findsNothing);
    expect(tester.takeException(), isNull);
  });

}
