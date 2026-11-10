import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/src/domains/common/widgets/styled_data_table.dart';

void main() {
  testWidgets('compact cards grow for Arabic text and keep action callbacks', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 720);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var rowTaps = 0;
    var actionTaps = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: MediaQuery(
            data: const MediaQueryData(textScaler: TextScaler.linear(1.5)),
            child: Scaffold(
              body: StyledDataTable(
                cardRows: true,
                columns: const [
                  StyledColumn(header: 'التفاصيل'),
                  StyledColumn(header: ''),
                ],
                onRowTap: (_) => rowTaps++,
                rows: [
                  [
                    const Text(
                      'محمد جعيد المتجر الصيني — فاتورة مشتريات تفصيلية طويلة',
                      key: Key('details'),
                    ),
                    IconButton(
                      key: const Key('edit'),
                      onPressed: () => actionTaps++,
                      icon: const Icon(Icons.edit),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    expect(find.byType(Card), findsOneWidget);
    await tester.tap(find.byKey(const Key('edit')));
    expect(actionTaps, 1);
    expect(rowTaps, 0);
    await tester.tap(find.byKey(const Key('details')));
    expect(rowTaps, 1);
    expect(tester.takeException(), isNull);
  });

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
