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
// ignore_for_file: depend_on_referenced_packages

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:growerp_models/growerp_models.dart';
import 'package:growerp_order_accounting/growerp_order_accounting.dart';
import 'package:intl/intl.dart';
import 'package:scrollable_positioned_list/scrollable_positioned_list.dart';

import 'balance_summary_filter.dart';

class BalanceSummaryList extends StatefulWidget {
  const BalanceSummaryList({super.key});

  @override
  BalanceSummaryListState createState() => BalanceSummaryListState();
}

class BalanceSummaryListState extends State<BalanceSummaryList> {
  final _itemScrollController = ItemScrollController();
  final _itemPositionsListener = ItemPositionsListener.create();
  final _searchController = TextEditingController();
  final _searchFocusNode = FocusNode();
  NumberFormat formatter = NumberFormat("00");
  late LedgerBloc _ledgerBloc;
  String _searchText = '';
  bool _sortAscending = true;
  late TimePeriod _selectedPeriod;
  late double bottom;
  double? right;
  late OrderAccountingLocalizations _localizations;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _searchFocusNode.requestFocus();
    });
    _selectedPeriod = TimePeriod(
      periodName: currentFiscalYearName(context),
      periodType: 'Y',
    );
    _ledgerBloc = context.read<LedgerBloc>()
      ..add(
        LedgerFetch(ReportType.summary, periodName: _selectedPeriod.periodName),
      );
    bottom = 50;
  }

  @override
  Widget build(BuildContext context) {
    _localizations = OrderAccountingLocalizations.of(context)!;
    right = right ?? (isAPhone(context) ? 20 : 50);
    return BlocConsumer<LedgerBloc, LedgerState>(
      listenWhen: (previous, current) =>
          previous.status == LedgerStatus.loading,
      listener: (context, state) {
        if (state.status == LedgerStatus.failure) {
          HelperFunctions.showMessage(context, '${state.message}', Colors.red);
        }
        if (state.status == LedgerStatus.success) {
          HelperFunctions.showMessage(
            context,
            translateLedgerBlocMessage(state.message, _localizations),
            Colors.green,
          );
        }
      },
      builder: (context, state) {
        switch (state.status) {
          case LedgerStatus.success:
            _selectedPeriod = state.ledgerReport!.period!;
            final sourceAccounts = state.ledgerReport!.glAccounts;
            final visibleAccounts = filterBalanceSummaryAccounts(
              sourceAccounts,
              query: _searchText,
              ascending: _sortAscending,
            );
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16.0,
                    vertical: 8.0,
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        children: [
                          IconButton(
                            icon: const Icon(Icons.arrow_back),
                            tooltip: _localizations.previousYear,
                            onPressed: () {
                              // Extract the year from the period name
                              String currentPeriodName =
                                  _selectedPeriod.periodName;
                              String yearStr = '';
                              int year = 0;

                              if (currentPeriodName.startsWith('Y')) {
                                // Format: Y2025
                                yearStr = currentPeriodName.substring(1);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              } else if (currentPeriodName.contains('q')) {
                                // Format: Y2025q1
                                yearStr = currentPeriodName.substring(1, 5);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              } else if (currentPeriodName.contains('m')) {
                                // Format: Y2025m01
                                yearStr = currentPeriodName.substring(1, 5);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              }

                              // Go to previous year
                              int previousYear = year - 1;

                              // Check if the previous year exists in available periods
                              bool yearExists = _checkYearExists(
                                previousYear,
                                state.timePeriods,
                              );

                              if (!yearExists) {
                                // Show message if year doesn't exist
                                HelperFunctions.showMessage(
                                  context,
                                  _localizations.dataForYearNotAvailable(
                                    previousYear.toString(),
                                  ),
                                  Colors.orange,
                                );
                                return;
                              }

                              // Determine the new period name based on current period type
                              String newPeriodName = '';
                              if (_selectedPeriod.periodType == 'Y') {
                                newPeriodName = 'Y$previousYear';
                              } else if (_selectedPeriod.periodType == 'Q') {
                                // Extract quarter number
                                int quarterIndex = currentPeriodName.indexOf(
                                  'q',
                                );
                                String quarter = currentPeriodName.substring(
                                  quarterIndex + 1,
                                );
                                newPeriodName = 'Y$previousYear${'q$quarter'}';
                              } else if (_selectedPeriod.periodType == 'M') {
                                // Extract month number
                                int monthIndex = currentPeriodName.indexOf('m');
                                String month = currentPeriodName.substring(
                                  monthIndex + 1,
                                );
                                newPeriodName = 'Y$previousYear${'m$month'}';
                              }

                              // Fetch data for the previous year
                              _ledgerBloc.add(
                                LedgerFetch(
                                  ReportType.summary,
                                  periodName: newPeriodName,
                                ),
                              );
                            },
                          ),
                          Text(
                            '${_localizations.year} ${_getYearFromPeriod(_selectedPeriod.periodName)}',
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                          IconButton(
                            icon: const Icon(Icons.arrow_forward),
                            tooltip: _localizations.nextYear,
                            onPressed: () {
                              // Extract the year from the period name
                              String currentPeriodName =
                                  _selectedPeriod.periodName;
                              String yearStr = '';
                              int year = 0;

                              if (currentPeriodName.startsWith('Y')) {
                                // Format: Y2025
                                yearStr = currentPeriodName.substring(1);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              } else if (currentPeriodName.contains('q')) {
                                // Format: Y2025q1
                                yearStr = currentPeriodName.substring(1, 5);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              } else if (currentPeriodName.contains('m')) {
                                // Format: Y2025m01
                                yearStr = currentPeriodName.substring(1, 5);
                                year =
                                    int.tryParse(yearStr) ??
                                    DateTime.now().year;
                              }

                              // Go to next year (but don't exceed current year)
                              int nextYear = year + 1;
                              int currentYear = currentFiscalYear(context);
                              if (nextYear > currentYear) {
                                nextYear = currentYear;
                              }

                              // Check if the next year exists in available periods
                              bool yearExists = _checkYearExists(
                                nextYear,
                                state.timePeriods,
                              );

                              if (!yearExists) {
                                // Show message if year doesn't exist
                                HelperFunctions.showMessage(
                                  context,
                                  _localizations.dataForYearNotAvailable(
                                    nextYear.toString(),
                                  ),
                                  Colors.orange,
                                );
                                return;
                              }

                              // Determine the new period name based on current period type
                              String newPeriodName = '';
                              if (_selectedPeriod.periodType == 'Y') {
                                newPeriodName = 'Y$nextYear';
                              } else if (_selectedPeriod.periodType == 'Q') {
                                // Extract quarter number
                                int quarterIndex = currentPeriodName.indexOf(
                                  'q',
                                );
                                String quarter = currentPeriodName.substring(
                                  quarterIndex + 1,
                                );
                                newPeriodName = 'Y$nextYear${'q$quarter'}';
                              } else if (_selectedPeriod.periodType == 'M') {
                                // Extract month number
                                int monthIndex = currentPeriodName.indexOf('m');
                                String month = currentPeriodName.substring(
                                  monthIndex + 1,
                                );
                                newPeriodName = 'Y$nextYear${'m$month'}';
                              }

                              // Fetch data for the next year
                              _ledgerBloc.add(
                                LedgerFetch(
                                  ReportType.summary,
                                  periodName: newPeriodName,
                                ),
                              );
                            },
                          ),
                        ],
                      ),
                      Text(
                        '${_localizations.period} ${_selectedPeriod.periodName}',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                    ],
                  ),
                ),
                ListFilterBar(
                  searchHint: _localizations.searchHintAccountCodeName,
                  searchController: _searchController,
                  focusNode: _searchFocusNode,
                  // This is a display-only filter. Do not issue accounting
                  // writes or recompute totals when the query changes.
                  onSearchChanged: (value) {
                    setState(() => _searchText = value);
                  },
                  actions: [
                    IconButton(
                      key: const Key('balanceSortDirection'),
                      tooltip: _sortAscending
                          ? _localizations.sortAccountCodeDescending
                          : _localizations.sortAccountCodeAscending,
                      onPressed: () {
                        setState(() => _sortAscending = !_sortAscending);
                      },
                      icon: Icon(
                        _sortAscending
                            ? Icons.arrow_downward
                            : Icons.arrow_upward,
                      ),
                    ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16.0,
                    vertical: 4.0,
                  ),
                  child: Column(
                    children: [
                      if (isPhone(context))
                        Text(
                          _localizations.glAccountName,
                          textAlign: TextAlign.center,
                        ),
                      Row(
                        children: <Widget>[
                          Expanded(
                            child: Text(
                              _localizations.code,
                              textAlign: TextAlign.center,
                            ),
                          ),
                          if (!isPhone(context))
                            Expanded(
                              child: Text(
                                _localizations.name,
                                textAlign: TextAlign.center,
                              ),
                            ),
                          Expanded(
                            child: Text(
                              _localizations.begin,
                              textAlign: TextAlign.right,
                            ),
                          ),
                          if (!isPhone(context))
                            Expanded(
                              child: Text(
                                _localizations.postedDebit,
                                textAlign: TextAlign.right,
                              ),
                            ),
                          if (!isPhone(context))
                            Expanded(
                              child: Text(
                                _localizations.postedCredit,
                                textAlign: TextAlign.right,
                              ),
                            ),
                          Expanded(
                            child: Text(
                              _localizations.endBalance,
                              textAlign: TextAlign.right,
                            ),
                          ),
                        ],
                      ),
                      const Divider(),
                    ],
                  ),
                ),
                Expanded(
                  child: Stack(
                    children: [
                      RefreshIndicator(
                        onRefresh: (() async => _ledgerBloc.add(
                          LedgerFetch(
                            ReportType.summary,
                            periodName: _selectedPeriod.periodName,
                          ),
                        )),
                        child: ScrollablePositionedList.builder(
                          key: const Key('listView'),
                          physics: const AlwaysScrollableScrollPhysics(),
                          itemCount: visibleAccounts.length + 1,
                          itemScrollController: _itemScrollController,
                          itemPositionsListener: _itemPositionsListener,
                          itemBuilder: (BuildContext context, int index) {
                            if (index == 0) {
                              if (visibleAccounts.isNotEmpty) {
                                return const SizedBox.shrink();
                              }
                              return Padding(
                                padding: const EdgeInsets.all(24),
                                child: Column(
                                  children: [
                                    Text(
                                      sourceAccounts.isEmpty
                                          ? _localizations.noBalanceSummaryFound
                                          : _localizations.noSearchResults,
                                      key: const Key('empty'),
                                      textAlign: TextAlign.center,
                                    ),
                                    if (sourceAccounts.isNotEmpty) ...[
                                      const SizedBox(height: 12),
                                      OutlinedButton.icon(
                                        key: const Key('clearBalanceSearch'),
                                        onPressed: () {
                                          _searchController.clear();
                                          setState(() => _searchText = '');
                                        },
                                        icon: const Icon(Icons.search_off),
                                        label: Text(_localizations.clearSearchFilter),
                                      ),
                                    ],
                                  ],
                                ),
                              );
                            }
                            index--;
                            return BalanceSummaryListItem(
                              glAccount: visibleAccounts[index],
                              index: index,
                            );
                          },
                        ),
                      ),
                      Positioned(
                        right: right,
                        bottom: bottom,
                        child: GestureDetector(
                          onPanUpdate: (details) {
                            setState(() {
                              right = right! - details.delta.dx;
                              bottom -= details.delta.dy;
                            });
                          },
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              // Only show buttons for period types that are not currently selected
                              // Year button
                              if (_selectedPeriod.periodType != 'Y')
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 8.0),
                                  child: FloatingActionButton(
                                    heroTag: 'yearButton',
                                    mini: true,
                                    backgroundColor: Colors.blue,
                                    child: Text(
                                      _localizations.yearLetter,
                                      style: const TextStyle(
                                        color: Colors.white,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                    onPressed: () => _ledgerBloc.add(
                                      LedgerFetch(
                                        ReportType.summary,
                                        periodName: _selectedPeriod.periodName
                                            .substring(0, 5),
                                      ),
                                    ),
                                  ),
                                ),
                              // Quarter button
                              if (_selectedPeriod.periodType != 'Q')
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 8.0),
                                  child: FloatingActionButton(
                                    heroTag: 'quarterButton',
                                    mini: true,
                                    backgroundColor: Colors.green,
                                    child: Text(
                                      _localizations.quarterLetter,
                                      style: const TextStyle(
                                        color: Colors.white,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                    onPressed: () {
                                      String currentQuarter = formatter.format(
                                        DateTime.now().month / 4 + 1,
                                      );
                                      _ledgerBloc.add(
                                        LedgerFetch(
                                          ReportType.summary,
                                          periodName:
                                              '${_selectedPeriod.periodName.substring(0, 5)}'
                                              'q$currentQuarter',
                                        ),
                                      );
                                    },
                                  ),
                                ),
                              // Month button
                              if (_selectedPeriod.periodType != 'M')
                                FloatingActionButton(
                                  heroTag: 'monthButton',
                                  mini: true,
                                  backgroundColor: Colors.orange,
                                  child: Text(
                                    _localizations.monthLetter,
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  onPressed: () => _ledgerBloc.add(
                                    LedgerFetch(
                                      ReportType.summary,
                                      periodName:
                                          '${_selectedPeriod.periodName.substring(0, 5)}'
                                          'm${formatter.format(DateTime.now().month)}',
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            );
          case LedgerStatus.failure:
            return FatalErrorForm(
              message: _localizations.getBalanceSummaryFail,
            );
          default:
            return const LoadingIndicator();
        }
      },
    );
  }

  // Helper method to extract year from period name
  String _getYearFromPeriod(String periodName) {
    if (periodName.startsWith('Y')) {
      if (periodName.contains('q') || periodName.contains('m')) {
        // Format: Y2025q1 or Y2025m01
        return periodName.substring(1, 5);
      } else {
        // Format: Y2025
        return periodName.substring(1);
      }
    }
    return DateTime.now().year.toString();
  }

  // Helper method to check if a year exists in the available time periods
  bool _checkYearExists(int year, List<TimePeriod> timePeriods) {
    String yearStr = year.toString();

    // Check if any period starts with the year we're looking for
    for (TimePeriod period in timePeriods) {
      if (period.periodName.startsWith('Y$yearStr')) {
        return true;
      }
    }

    return false;
  }

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }
}
