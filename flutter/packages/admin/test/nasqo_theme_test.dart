import 'package:admin/theme/nasqo_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final brightness in Brightness.values) {
    test('accounting controls retain readable contrast in $brightness', () {
      final theme = buildNasqoTheme(ThemeData(brightness: brightness));
      expect(theme.brightness, brightness);
      double contrast(Color a, Color b) {
        final values = [a.computeLuminance(), b.computeLuminance()]..sort();
        return (values.last + .05) / (values.first + .05);
      }

      expect(
        contrast(
          theme.appBarTheme.backgroundColor!,
          theme.appBarTheme.foregroundColor!,
        ),
        greaterThan(4.5),
      );
      expect(
        contrast(theme.colorScheme.primary, theme.colorScheme.onPrimary),
        greaterThan(4.5),
      );
      expect(
        contrast(
          theme.floatingActionButtonTheme.backgroundColor!,
          theme.floatingActionButtonTheme.foregroundColor!,
        ),
        greaterThan(4.5),
      );
    });
  }
}
