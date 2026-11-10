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

import 'popup.dart';

/// Fixed-body adapter for the shared popup used by existing callers.
Widget popUpDialog({
  required BuildContext context,
  required List<Widget> children,
  String title = '',
  double height = 400,
  double width = 400,
  Key key = const Key('popUp'),
}) {
  return Dialog(
    key: key,
    insetPadding: const EdgeInsets.all(10),
    clipBehavior: Clip.antiAlias,
    child: popUp(
      context: context,
      title: title,
      height: height,
      width: width,
      padding: 20,
      child: Column(children: children),
    ),
  );
}
