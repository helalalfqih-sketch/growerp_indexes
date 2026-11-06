import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:global_configuration/global_configuration.dart';
import 'package:go_router/go_router.dart';
import 'package:growerp_activity/l10n/generated/activity_localizations.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'shared/mocks.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    GlobalConfiguration().loadFromMap({'appName': '', 'test': false});
  });

  test('Arabic resources include all six plural forms', () async {
    expect(CoreLocalizations.supportedLocales, contains(const Locale('ar')));
    final ar = await CoreLocalizations.delegate.load(const Locale('ar'));
    expect(ar.selectLanguage, 'اختيار اللغة');
    expect(ar.calendarBacklog(0), 'لا توجد منشورات بلا تاريخ');
    expect(ar.calendarBacklog(1), 'منشور واحد بلا تاريخ');
    expect(ar.calendarBacklog(2), 'منشوران بلا تاريخ');
    expect(ar.calendarBacklog(3), '3 منشورات بلا تاريخ');
    expect(ar.calendarBacklog(11), '11 منشورًا بلا تاريخ');
    expect(ar.calendarBacklog(100), '100 منشور بلا تاريخ');
  });

  Future<void> pumpHome(WidgetTester tester) async {
    final company = Company(
      partyId: 'C1',
      name: 'شركة الاختبار',
      // Local image keeps this UI test independent of asset bundles and HTTP.
      image: base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      ),
    );
    final restClient = MockRestClient();
    when(
      () => restClient.getCompanies(searchString: 'C1', limit: 1),
    ).thenAnswer((_) async => Companies(companies: [company]));
    when(
      () => restClient.getUom(['UT_CURRENCY_MEASURE']),
    ).thenAnswer((_) async => Uoms());
    final chat = MockWsClient();
    final notification = MockWsClient();
    for (final client in [chat, notification]) {
      when(() => client.stream()).thenAnswer((_) => const Stream.empty());
      when(() => client.close()).thenReturn(null);
    }
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => const HomeForm(
            menuConfiguration: MenuConfiguration(appId: 'admin', name: 'Admin'),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      TopApp(
        restClient: restClient,
        applicationId: 'AppAdmin',
        chatClient: chat,
        notificationClient: notification,
        company: company,
        router: router,
        extraDelegates: const [
          ActivityLocalizations.delegate,
          _EnglishMessagesDelegate(),
        ],
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final size in [const Size(412, 900), const Size(1280, 800)]) {
    testWidgets('language picker switches and persists Arabic at $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await pumpHome(tester);

      await tester.tap(find.byKey(const Key('languageSelector')));
      await tester.pumpAndSettle();
      expect(find.text('العربية'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('language_ar')));
      await tester.pumpAndSettle();

      final context = tester.element(find.byType(HomeForm));
      expect(Localizations.localeOf(context), const Locale('ar'));
      expect(Directionality.of(context), TextDirection.rtl);
      expect(ActivityLocalizations.of(context)!.activity_price, 'السعر');
      expect(
        Localizations.of<_EnglishMessages>(context, _EnglishMessages)!.label,
        'Optional module',
      );
      expect(find.text(CoreLocalizations.of(context)!.login), findsOneWidget);
      expect(await LocaleBloc.savedLanguageCode(), 'ar');
      expect(tester.takeException(), isNull);

      // Recreate the real app shell: the next launch must restore Arabic.
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      await pumpHome(tester);
      final restored = tester.element(find.byType(HomeForm));
      expect(Localizations.localeOf(restored), const Locale('ar'));
      expect(Directionality.of(restored), TextDirection.rtl);

      // The same picker must still let the user return to an LTR language.
      await tester.tap(find.byKey(const Key('languageSelector')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('language_en')));
      await tester.pumpAndSettle();
      expect(Directionality.of(restored), TextDirection.ltr);
      expect(await LocaleBloc.savedLanguageCode(), 'en');
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
    });
  }
}

class _EnglishMessages {
  const _EnglishMessages();
  String get label => 'Optional module';
}

class _EnglishMessagesDelegate extends LocalizationsDelegate<_EnglishMessages> {
  const _EnglishMessagesDelegate();

  @override
  bool isSupported(Locale locale) => locale.languageCode == 'en';

  @override
  Future<_EnglishMessages> load(Locale locale) {
    if (!isSupported(locale)) {
      throw FlutterError('This test module has no resources for $locale');
    }
    return SynchronousFuture(const _EnglishMessages());
  }

  @override
  bool shouldReload(_EnglishMessagesDelegate old) => false;
}
