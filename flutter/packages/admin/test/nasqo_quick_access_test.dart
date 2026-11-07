import 'package:admin/l10n/generated/admin_localizations.dart';
import 'package:admin/views/nasqo_quick_access.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_models/growerp_models.dart';

void main() {
  test('quick actions respect the supplied visible menu and priority', () {
    const items = [
      MenuItem(title: 'Other', route: '/marketing', sequenceNum: 1),
      MenuItem(title: 'Inventory', route: '/inventory', sequenceNum: 90),
      MenuItem(title: 'Orders', route: '/orders', sequenceNum: 80),
      MenuItem(title: 'Hidden', route: '/acct-sales', isActive: false),
      MenuItem(title: 'Minimized', route: '/catalog', isMinimized: true),
      MenuItem(title: 'Child', route: '/crm', parentMenuItemId: 'ADMIN_CRM'),
      MenuItem(title: 'Root', route: '/'),
      MenuItem(
        title: 'Admin only',
        route: '/systemSetup',
        userGroups: [UserGroup.admin],
      ),
      MenuItem(title: 'Duplicate orders', route: '/orders'),
    ];

    expect(
      selectNasqoQuickActions(
        items,
        userGroup: UserGroup.employee,
      ).map((item) => item.route).toList(),
      ['/orders', '/inventory', '/marketing'],
    );
    expect(
      selectNasqoQuickActions(
        items,
        userGroup: UserGroup.admin,
      ).map((item) => item.route).toList(),
      contains('/systemSetup'),
    );
  });

  testWidgets('Arabic home shows quick actions and opens the chosen route', (
    tester,
  ) async {
    String? selectedRoute;
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('ar'),
        localizationsDelegates: AdminLocalizations.localizationsDelegates,
        supportedLocales: AdminLocalizations.supportedLocales,
        home: Scaffold(
          body: NasqoQuickAccess(
            items: const [MenuItem(title: 'orders', route: '/orders')],
            onNavigate: (route) => selectedRoute = route,
          ),
        ),
      ),
    );

    expect(find.text('نسقو ERP'), findsOneWidget);
    expect(find.text('وصول سريع'), findsOneWidget);
    expect(find.text('الطلبات'), findsOneWidget);
    expect(find.byKey(const Key('nasqo-quick-/acct-sales')), findsNothing);
    await tester.tap(find.byKey(const Key('nasqo-quick-/orders')));
    expect(selectedRoute, '/orders');
    final context = tester.element(find.text('الطلبات'));
    expect(Directionality.of(context), TextDirection.rtl);
  });
}
