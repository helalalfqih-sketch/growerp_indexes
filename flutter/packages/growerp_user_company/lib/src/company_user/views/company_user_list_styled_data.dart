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
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_models/growerp_models.dart';

import '../company_user.dart';
import 'package:growerp_user_company/l10n/generated/user_company_localizations.dart';

/// Returns column definitions for company user list based on device type
List<StyledColumn> getCompanyUserListColumns(BuildContext context) {
  final localizations = UserCompanyLocalizations.of(context)!;
  bool isPhone = isAPhone(context);

  if (isPhone) {
    return [
      StyledColumn(header: '', flex: 1), // Avatar
      StyledColumn(header: localizations.tableHdrInfo, flex: 6),
      StyledColumn(header: '', flex: 1), // Open details
    ];
  }

  return [
    StyledColumn(header: localizations.id, flex: 1),
    StyledColumn(header: localizations.tableHdrT, flex: 1), // Type
    StyledColumn(header: localizations.name, flex: 3),
    StyledColumn(header: localizations.role, flex: 1),
    StyledColumn(header: localizations.email, flex: 2),
    StyledColumn(header: localizations.tableHdrPhone, flex: 1),
    StyledColumn(header: localizations.location, flex: 2),
    StyledColumn(header: '', flex: 1), // Actions
  ];
}

/// Returns row data for company user list
List<Widget> getCompanyUserListRow({
  required BuildContext context,
  required CompanyUser companyUser,
  required int index,
  required Bloc bloc,
}) {
  final isPhone = isAPhone(context);
  final localizations = UserCompanyLocalizations.of(context)!;
  final name = (companyUser.name ?? '').trim();
  final phone = (companyUser.telephoneNr ?? '').trim();
  final contact = phone.isNotEmpty ? phone : (companyUser.email ?? '').trim();
  final typeLabel = companyUser.type == PartyType.company
      ? localizations.company
      : localizations.person;
  List<Widget> cells = [];

  if (isPhone) {
    // Focus on the name and contact rather than consuming mobile width with
    // source IDs and O/P codes. Details still show the full identifier.
    cells.add(
      Tooltip(
        message: typeLabel,
        child: CircleAvatar(
          child: companyUser.image != null
              ? Image.memory(companyUser.image!)
              : Text(name.isEmpty ? '?' : name.substring(0, 1)),
        ),
      ),
    );

    cells.add(
      Column(
        key: Key('item$index'),
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            name,
            key: Key('name$index'),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
          Row(
            children: [
              Text(
                typeLabel,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (contact.isNotEmpty) ...[
                const SizedBox(width: 6),
                Flexible(
                  child: Text(
                    contact,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textDirection: TextDirection.ltr,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  } else {
    // ID
    cells.add(
      SizedBox(
        key: Key('item$index'),
        child: Text(
          companyUser.pseudoId ?? '',
          key: Key('id$index'),
          textDirection: TextDirection.ltr,
        ),
      ),
    );

    // Type indicator
    cells.add(
      Text(
        companyUser.type == PartyType.company
            ? 'O'
            : companyUser.type == PartyType.user
            ? 'P'
            : '?',
        key: Key('type$index'),
      ),
    );

    // Name
    cells.add(Text(companyUser.name ?? '', key: Key('name$index')));

    // Role
    cells.add(
      Text(
        companyUser.role != null ? companyUser.role!.value : Role.unknown.value,
        key: Key('role$index'),
      ),
    );

    // Email
    cells.add(Text(companyUser.email ?? '', key: Key('email$index')));

    // Phone
    cells.add(
      Text(
        companyUser.telephoneNr ?? '',
        key: Key('telephone$index'),
        textDirection: TextDirection.ltr,
      ),
    );

    // Location
    cells.add(
      Text(
        "${companyUser.address?.country ?? ''} ${companyUser.address?.city ?? ''}",
        key: Key('location$index'),
      ),
    );
  }

  // The former list-level delete button dispatched CompanyUserDelete()
  // without a user, which can crash. Open the detail dialog via row tap;
  // destructive user actions are confirmed there instead.
  cells.add(
    Icon(
      Directionality.of(context) == TextDirection.rtl
          ? Icons.chevron_left
          : Icons.chevron_right,
      semanticLabel: localizations.name,
    ),
  );

  return cells;
}
