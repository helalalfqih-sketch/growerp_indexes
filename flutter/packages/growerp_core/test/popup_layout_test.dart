import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/src/domains/common/widgets/popup_dialog.dart';
import 'package:growerp_core/src/domains/common/widgets/popup_dialog_no_scaf.dart';

void main() {
  for (final scrollable in [false, true]) {
    testWidgets(
      'shared popup preserves close and RTL title (scroll: $scrollable)',
      (tester) async {
        tester.view.physicalSize = const Size(320, 600);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          MaterialApp(
            home: Directionality(
              textDirection: TextDirection.rtl,
              child: Builder(
                builder: (context) => Scaffold(
                  body: TextButton(
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (context) {
                        final buildDialog = scrollable
                            ? popUpDialogNoScaffold
                            : popUpDialog;
                        return Directionality(
                          textDirection: TextDirection.rtl,
                          child: buildDialog(
                            context: context,
                            title: 'إضافة حساب جديد برصيد افتتاحي',
                            children: const [
                              TextField(
                                decoration: InputDecoration(labelText: 'الاسم'),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('topHeader')), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.tap(find.byKey(const Key('cancel')));
        await tester.pumpAndSettle();
        expect(find.byType(Dialog), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
