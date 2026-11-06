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
}
