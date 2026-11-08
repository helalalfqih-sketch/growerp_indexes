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

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:growerp_models/growerp_models.dart';

import '../../common/functions/open_external_url.dart';

/// NASQO's native browser-based Google sign-in with manual device confirmation.
///
/// Google signs in in the user's regular browser; this dialog keeps a separate
/// random device secret in memory and shows only an eight-character pairing
/// code for manual confirmation. The Google callback never receives the device
/// secret, so a copied Google link cannot log in an unrelated native app.
class GoogleDeviceLoginDialog extends StatefulWidget {
  const GoogleDeviceLoginDialog({
    super.key,
    required this.restClient,
    required this.applicationId,
  });

  final RestClient restClient;
  final String applicationId;

  @override
  State<GoogleDeviceLoginDialog> createState() =>
      _GoogleDeviceLoginDialogState();
}

class _GoogleDeviceLoginDialogState extends State<GoogleDeviceLoginDialog> {
  Timer? _pollTimer;
  String? _authUrl;
  String? _deviceCode;
  String? _pairingCode;
  String? _message;
  DateTime? _expiresAt;
  bool _loading = true;
  bool _openedBrowser = false;
  bool _polling = false;
  bool _finished = false;

  bool get _arabic => Localizations.localeOf(context).languageCode == 'ar';

  String _text(String arabic, String english) => _arabic ? arabic : english;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    _pollTimer?.cancel();
    setState(() {
      _loading = true;
      _authUrl = null;
      _deviceCode = null;
      _pairingCode = null;
      _message = null;
      _openedBrowser = false;
      _finished = false;
    });
    try {
      final result = await widget.restClient.startGoogleDeviceLogin(
        applicationId: widget.applicationId,
      );
      if (!mounted) return;
      final url = (result['authUrl'] ?? '').toString();
      final device = (result['deviceCode'] ?? '').toString();
      final code = (result['pairingCode'] ?? '').toString();
      final lifetime = result['expiresInSeconds'];
      if (Uri.tryParse(url)?.host != 'accounts.google.com' ||
          !url.startsWith('https://') ||
          !RegExp(r'^[A-Za-z0-9_-]{40,100}$').hasMatch(device) ||
          !RegExp(r'^[A-Z2-9]{8}$').hasMatch(code) ||
          lifetime is! num ||
          lifetime <= 0 ||
          lifetime > 600) {
        throw StateError('The server returned an invalid Google login request');
      }
      setState(() {
        _authUrl = url;
        _deviceCode = device;
        _pairingCode = code;
        _expiresAt = DateTime.now().add(Duration(seconds: lifetime.toInt()));
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _message = _text(
          'تعذّر إعداد الدخول عبر Google. تحقق من إعدادات نسقو واتصال الإنترنت.',
          'Could not start Google sign-in. Check NASQO settings and connectivity.',
        );
      });
    }
  }

  Future<void> _openBrowser() async {
    final url = _authUrl;
    if (url == null || _finished) return;
    try {
      final opened = await openExternalUrl(Uri.parse(url));
      if (!mounted) return;
      if (!opened) {
        setState(
          () => _message = _text(
            'تعذّر فتح متصفح Google.',
            'Could not open the Google browser.',
          ),
        );
        return;
      }
      setState(() {
        _openedBrowser = true;
        _message = null;
      });
      _pollTimer?.cancel();
      _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _poll());
      await _poll();
    } catch (_) {
      if (!mounted) return;
      setState(
        () => _message = _text(
          'حدث خطأ عند فتح متصفح Google.',
          'An error occurred while opening Google sign-in.',
        ),
      );
    }
  }

  Future<void> _poll() async {
    final code = _deviceCode;
    if (!mounted || _polling || _finished || code == null || !_openedBrowser) {
      return;
    }
    if (_expiresAt != null && DateTime.now().isAfter(_expiresAt!)) {
      _pollTimer?.cancel();
      setState(() {
        _finished = true;
        _message = _text(
          'انتهت مهلة التسجيل. أغلق النافذة وابدأ من جديد.',
          'Sign-in expired. Close and start again.',
        );
      });
      return;
    }
    _polling = true;
    try {
      final result = await widget.restClient.pollGoogleDeviceLogin(
        applicationId: widget.applicationId,
        deviceCode: code,
      );
      if (!mounted || _finished) return;
      if (result['status'] == 'pending') return;
      if (result['status'] != 'authenticated' ||
          result['authenticate'] is! Map) {
        throw StateError('Invalid native login response');
      }
      final auth = Authenticate.fromJson(
        Map<String, dynamic>.from(result['authenticate'] as Map),
      );
      if (auth.apiKey == null ||
          auth.apiKey!.isEmpty ||
          auth.user?.userId == null) {
        throw StateError('NASQO did not return an authenticated user');
      }
      _finished = true;
      _pollTimer?.cancel();
      Navigator.of(context).pop(auth);
    } catch (_) {
      if (!mounted || _finished) return;
      _pollTimer?.cancel();
      setState(() {
        _message = _text(
          'لم تكتمل المصادقة. تحقق من رمز المطابقة ثم اختر إعادة المحاولة.',
          'Sign-in did not complete. Check the pairing code and try again.',
        );
      });
    } finally {
      _polling = false;
    }
  }

  @override
  void dispose() {
    _finished = true;
    _pollTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_text('تسجيل الدخول باستخدام Google', 'Sign in with Google')),
      content: SizedBox(
        width: 410,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_loading) ...[
                const Center(child: CircularProgressIndicator()),
                const SizedBox(height: 12),
                Text(
                  _text(
                    'جارٍ تحضير الاتصال الآمن...',
                    'Preparing a secure sign-in...',
                  ),
                ),
              ] else if (_pairingCode != null && !_finished) ...[
                Text(
                  _text(
                    'افتح Google في المتصفح، ثم أدخل الرمز التالي في صفحة التأكيد بعد تسجيل الدخول:',
                    'Open Google in your browser, then enter this code on the confirmation page after signing in:',
                  ),
                ),
                const SizedBox(height: 16),
                SelectableText(
                  _pairingCode!,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    letterSpacing: 2,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: () =>
                      Clipboard.setData(ClipboardData(text: _pairingCode!)),
                  icon: const Icon(Icons.copy),
                  label: Text(_text('نسخ الرمز', 'Copy code')),
                ),
                const SizedBox(height: 8),
                FilledButton.icon(
                  key: const Key('googleDeviceOpenBrowser'),
                  onPressed: _openBrowser,
                  icon: const Icon(Icons.open_in_browser),
                  label: Text(_text('فتح Google', 'Open Google')),
                ),
                if (_openedBrowser) ...[
                  const SizedBox(height: 12),
                  const Center(child: LinearProgressIndicator()),
                  const SizedBox(height: 8),
                  Text(
                    _text(
                      'بعد التأكيد في المتصفح، عُد إلى نسقو ليكتمل الدخول تلقائيًا.',
                      'After confirming in your browser, return to NASQO to finish signing in.',
                    ),
                  ),
                  TextButton(
                    onPressed: _polling ? null : _poll,
                    child: Text(_text('تحقق الآن', 'Check now')),
                  ),
                ],
              ],
              if (_message != null) ...[
                const SizedBox(height: 12),
                Text(
                  _message!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              if (!_loading && _pairingCode == null)
                TextButton(
                  onPressed: _start,
                  child: Text(_text('إعادة المحاولة', 'Try again')),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_text('إلغاء', 'Cancel')),
        ),
      ],
    );
  }
}
