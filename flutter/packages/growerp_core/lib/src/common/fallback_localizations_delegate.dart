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

import 'package:flutter/widgets.dart';

/// Loads a module's English resources when it has not yet been translated into
/// the application locale. The application locale and text direction stay intact.
class FallbackLocalizationsDelegate<T> extends LocalizationsDelegate<T> {
  const FallbackLocalizationsDelegate(this.delegate);

  final LocalizationsDelegate<T> delegate;
  static const _fallbackLocale = Locale('en');

  // TopApp receives a heterogeneous List<LocalizationsDelegate>. Preserve each
  // resource type even when T is inferred as dynamic, otherwise Flutter would
  // retain only the first delegate and other modules' .of(context) would be null.
  @override
  Type get type => delegate.type;

  @override
  bool isSupported(Locale locale) =>
      delegate.isSupported(locale) || delegate.isSupported(_fallbackLocale);

  @override
  Future<T> load(Locale locale) =>
      delegate.load(delegate.isSupported(locale) ? locale : _fallbackLocale);

  @override
  bool shouldReload(covariant FallbackLocalizationsDelegate<T> old) =>
      delegate.runtimeType != old.delegate.runtimeType ||
      delegate.shouldReload(old.delegate);
}
