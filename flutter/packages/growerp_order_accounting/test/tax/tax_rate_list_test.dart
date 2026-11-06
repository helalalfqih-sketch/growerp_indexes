import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/growerp_order_accounting.dart';
import 'package:mocktail/mocktail.dart';
import 'package:responsive_framework/responsive_framework.dart';

import '../shared/mocks.dart';

void main() {
  late MockRestClient restClient;

  final vat = TaxRate(
    taxRateId: 'R1',
    taxName: 'VAT',
    taxPercentage: Decimal.fromInt(21),
  );

  setUpAll(() => registerFallbackValue(TaxRate()));
  setUp(() {
    restClient = MockRestClient();
    when(
      () => restClient.getTaxRates(),
    ).thenAnswer((_) async => TaxRates(taxRates: [vat]));
    when(
      () => restClient.getCategory(limit: 100),
    ).thenAnswer((_) async => Categories());
  });

  Future<void> pumpList(
    WidgetTester tester, {
    Locale locale = const Locale('en'),
  }) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        locale: locale,
        supportedLocales: CoreLocalizations.supportedLocales,
        builder: (context, child) => ResponsiveBreakpoints.builder(
          child: child!,
          breakpoints: const [
            Breakpoint(start: 0, end: 500, name: MOBILE),
            Breakpoint(start: 501, end: 1920, name: DESKTOP),
          ],
        ),
        localizationsDelegates: [
          ...CoreLocalizations.localizationsDelegates,
          OrderAccountingLocalizations.delegate,
        ],
        home: Scaffold(
          body: BlocProvider(
            create: (_) => TaxRateBloc(restClient),
            child: const TaxRateList(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('the list shows a rate and its row opens the dialog', (
    tester,
  ) async {
    await pumpList(tester);
    expect(find.byKey(const Key('name0')), findsOneWidget);
    expect(find.text('VAT'), findsOneWidget);
    expect(find.byKey(const Key('pricesIncludeTax')), findsOneWidget);

    await tester.tap(find.byKey(const Key('name0')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('TaxRateDialog')), findsOneWidget);
    expect(find.byKey(const Key('delete')), findsOneWidget);
  });

  testWidgets('a new rate needs a name and a percentage', (tester) async {
    await pumpList(tester);
    await tester.tap(find.byKey(const Key('addNew')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('delete')), findsNothing);

    await tester.tap(find.byKey(const Key('update')));
    await tester.pumpAndSettle();
    verifyNever(() => restClient.createTaxRate(taxRate: any(named: 'taxRate')));
  });

  testWidgets('Arabic tax dialog validates and submits a rate in RTL', (
    tester,
  ) async {
    await pumpList(tester, locale: const Locale('ar'));
    expect(find.text('أسعار نقاط البيع شاملة الضريبة'), findsOneWidget);
    await tester.tap(find.byKey(const Key('addNew')));
    await tester.pumpAndSettle();

    final context = tester.element(find.byKey(const Key('TaxRateDialog')));
    expect(Directionality.of(context), TextDirection.rtl);
    expect(Localizations.localeOf(context), const Locale('ar'));
    final ar = OrderAccountingLocalizations.of(context)!;
    expect(find.text(ar.newTaxRate), findsOneWidget);

    await tester.tap(find.byKey(const Key('update')));
    await tester.pumpAndSettle();
    final nameField = tester.state<FormFieldState<String>>(
      find.byKey(const Key('name')),
    );
    final percentageField = tester.state<FormFieldState<String>>(
      find.byKey(const Key('percentage')),
    );
    expect(nameField.errorText, ar.taxRateName);
    expect(percentageField.errorText, ar.taxRatePercentage);
    verifyNever(() => restClient.createTaxRate(taxRate: any(named: 'taxRate')));

    when(
      () => restClient.createTaxRate(taxRate: any(named: 'taxRate')),
    ).thenAnswer((invocation) async {
      final rate = invocation.namedArguments[#taxRate] as TaxRate;
      return rate.copyWith(taxRateId: 'R2');
    });
    await tester.enterText(find.byKey(const Key('name')), 'ضريبة المبيعات');
    await tester.enterText(find.byKey(const Key('percentage')), '15');
    await tester.tap(find.byKey(const Key('update')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('TaxRateDialog')), findsNothing);
    expect(find.text('ضريبة المبيعات'), findsOneWidget);
    final submitted =
        verify(
              () => restClient.createTaxRate(
                taxRate: captureAny(named: 'taxRate'),
              ),
            ).captured.single
            as TaxRate;
    expect(submitted.taxName, 'ضريبة المبيعات');
    expect(submitted.taxPercentage, Decimal.fromInt(15));
    expect(find.text(ar.taxRateAddSuccess), findsOneWidget);
    // Let the success notification's auto-dismiss timer complete.
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
