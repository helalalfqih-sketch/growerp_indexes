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

import '../functions/screen_size.dart';
import 'dialog_close_button.dart';

/// Shared dialog body for account, invoice and inventory editors.
Widget popUp({
  Widget? child,
  String title = '',
  double height = 400,
  double? width,
  double padding = 10,
  bool? closeButton = true,
  List<Widget>? actions,
  required BuildContext context,
}) {
  final colorScheme = Theme.of(context).colorScheme;

  if (width == null) {
    isPhone(context) ? width = 450 : width = 700;
  }
  return SizedBox(
    width: width,
    height: height,
    child: Column(
      children: [
        Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(20, 12, 12, 12),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  title,
                  key: const Key('topHeader'),
                  style:
                      Theme.of(context).dialogTheme.titleTextStyle ??
                      Theme.of(context).textTheme.titleLarge,
                ),
              ),
              if (actions != null) ...actions,
              if (closeButton == true) const DialogCloseButton(),
            ],
          ),
        ),
        Expanded(
          child: Material(
            color: Theme.of(context).brightness == Brightness.dark
                ? colorScheme.surfaceContainerHighest
                : colorScheme.surface,
            child: Padding(padding: EdgeInsets.all(padding), child: child),
          ),
        ),
      ],
    ),
  );
}
