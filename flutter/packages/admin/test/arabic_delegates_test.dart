import 'package:admin/main.dart' as admin;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_order_accounting/growerp_order_accounting.dart';

void main() {
  for (final locale in [
    const Locale('ar'),
    const Locale('en'),
    const Locale('de'),
  ]) {
    testWidgets('all Admin modules remain available in $locale', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          locale: locale,
          supportedLocales: CoreLocalizations.supportedLocales,
          localizationsDelegates: [
            ...CoreLocalizations.localizationsDelegates,
            ...admin.delegates.map(FallbackLocalizationsDelegate.new),
          ],
          home: Builder(
            builder: (context) {
              for (final delegate in admin.delegates) {
                // The actual heterogeneous app list is intentional: losing a
                // delegate's type when wrapping it would make this lookup null.
                final dynamic resource = Localizations.of(
                  context,
                  delegate.type,
                );
                expect(
                  resource,
                  isNotNull,
                  reason: '${delegate.type} in $locale',
                );
                expect(
                  resource.localeName,
                  delegate.isSupported(locale) ? locale.languageCode : 'en',
                );
              }
              final accounting = OrderAccountingLocalizations.of(context)!;
              return Scaffold(body: Text(accounting.trialBalance));
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      final context = tester.element(find.byType(Scaffold));
      expect(Localizations.localeOf(context), locale);
      expect(
        Directionality.of(context),
        locale.languageCode == 'ar' ? TextDirection.rtl : TextDirection.ltr,
      );
      if (locale.languageCode == 'ar') {
        expect(find.text('ميزان المراجعة'), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
    });
  }
}
