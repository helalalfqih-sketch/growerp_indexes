/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import 'package:flutter/material.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_models/growerp_models.dart';

import '../l10n/generated/admin_localizations.dart';

/// A focused subset of the menu that was already supplied for this user.
///
/// We never invent routes or use an unfiltered global menu: the existing
/// dynamic menu and router remain responsible for access checks.
List<MenuItem> selectNasqoQuickActions(
  List<MenuItem> menuItems, {
  UserGroup? userGroup,
}) {
  const preferredRoutes = [
    '/orders',
    '/acct-sales',
    '/acct-purchase',
    '/inventory',
    '/catalog',
    '/crm',
    '/acct-ledger',
  ];

  final available = <String, MenuItem>{};
  for (final item in menuItems) {
    final route = item.route;
    if ((item.userGroups?.isNotEmpty ?? false) &&
        !item.userGroups!.contains(userGroup)) {
      continue;
    }
    if (!item.isActive ||
        item.isMinimized ||
        item.parentMenuItemId != null ||
        route == null ||
        route.isEmpty ||
        route == '/' ||
        route == '/about' ||
        route.startsWith('/accounting/')) {
      continue;
    }
    available.putIfAbsent(route, () => item);
  }

  final result = <MenuItem>[
    for (final route in preferredRoutes)
      if (available.containsKey(route)) available.remove(route)!,
  ];
  final remaining = available.values.toList()
    ..sort((a, b) => a.sequenceNum.compareTo(b.sequenceNum));
  result.addAll(remaining);
  return result.take(8).toList();
}

class NasqoQuickAccess extends StatelessWidget {
  const NasqoQuickAccess({
    super.key,
    required this.items,
    required this.onNavigate,
    this.userGroup,
    this.stats,
  });

  final List<MenuItem> items;
  final ValueChanged<String> onNavigate;
  final UserGroup? userGroup;
  final Stats? stats;

  String _label(BuildContext context, MenuItem item) {
    final l10n = AdminLocalizations.of(context)!;
    return switch (item.route) {
      '/orders' => l10n.nasqoOrders,
      '/acct-sales' => l10n.nasqoInvoices,
      '/inventory' => l10n.nasqoInventory,
      '/catalog' => l10n.nasqoProducts,
      '/crm' => l10n.nasqoCrm,
      '/acct-ledger' => l10n.nasqoAccounting,
      _ => _fallbackLabel(context, item.title),
    };
  }

  String _fallbackLabel(BuildContext context, String title) {
    final core = CoreLocalizations.of(context);
    return core == null
        ? title
        : HelperFunctions.translateMenuTitle(core, title);
  }

  IconData _icon(String route) => switch (route) {
    '/orders' => Icons.shopping_bag_outlined,
    '/acct-sales' => Icons.receipt_long_outlined,
    '/acct-purchase' => Icons.shopping_cart_outlined,
    '/inventory' => Icons.warehouse_outlined,
    '/catalog' => Icons.inventory_2_outlined,
    '/crm' => Icons.people_alt_outlined,
    '/acct-ledger' => Icons.account_balance_outlined,
    _ => Icons.apps_outlined,
  };

  @override
  Widget build(BuildContext context) {
    final l10n = AdminLocalizations.of(context)!;
    final theme = Theme.of(context);
    final actions = selectNasqoQuickActions(items, userGroup: userGroup);

    return LayoutBuilder(
      builder: (context, constraints) {
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1080),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l10n.nasqoWelcomeTitle,
                      style: theme.textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.primary,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      l10n.nasqoWelcomeSubtitle,
                      style: theme.textTheme.bodyLarge,
                    ),
                    const SizedBox(height: 28),
                    Text(
                      l10n.nasqoQuickAccessTitle,
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      l10n.nasqoQuickAccessSubtitle,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (actions.isEmpty)
                      Text(l10n.nasqoNoQuickActions)
                    else
                      LayoutBuilder(
                        builder: (context, width) {
                          const gap = 12.0;
                          final columns = width.maxWidth >= 820
                              ? 3
                              : width.maxWidth >= 300
                              ? 2
                              : 1;
                          final tileWidth =
                              (width.maxWidth - gap * (columns - 1)) / columns;
                          return Wrap(
                            spacing: gap,
                            runSpacing: gap,
                            children: [
                              for (final item in actions)
                                SizedBox(
                                  width: tileWidth,
                                  child: Card(
                                    margin: EdgeInsets.zero,
                                    clipBehavior: Clip.antiAlias,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(14),
                                      side: BorderSide(
                                        color: theme.colorScheme.primary,
                                        width: 1.5,
                                      ),
                                    ),
                                    child: InkWell(
                                      key: Key('nasqo-quick-${item.route}'),
                                      onTap: () => onNavigate(item.route!),
                                      child: Padding(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 16,
                                          vertical: 20,
                                        ),
                                        child: Column(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            CircleAvatar(
                                              radius: 28,
                                              backgroundColor: theme
                                                  .colorScheme
                                                  .primaryContainer,
                                              child: Icon(
                                                _icon(item.route!),
                                                size: 30,
                                                color:
                                                    theme.colorScheme.primary,
                                              ),
                                            ),
                                            const SizedBox(height: 14),
                                            Text(
                                              _label(context, item),
                                              textAlign: TextAlign.center,
                                              style: theme.textTheme.titleMedium
                                                  ?.copyWith(
                                                    color: theme
                                                        .colorScheme
                                                        .primary,
                                                    fontWeight: FontWeight.w600,
                                                  ),
                                            ),
                                            if (getStatsForRoute(
                                                  context,
                                                  item.route,
                                                  stats,
                                                )
                                                case final String summary) ...[
                                              const SizedBox(height: 8),
                                              Text(
                                                summary,
                                                textAlign: TextAlign.center,
                                                style:
                                                    theme.textTheme.bodySmall,
                                              ),
                                            ],
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                            ],
                          );
                        },
                      ),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}
