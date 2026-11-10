import 'package:flutter/material.dart';

/// Nasqo's accounting palette, shared by the existing domain screens.
/// Keep typography, transitions and brightness from the user's base theme.
ThemeData buildNasqoTheme(ThemeData base) {
  final dark = base.brightness == Brightness.dark;
  final primary = dark ? const Color(0xFF8AC4FF) : const Color(0xFF07549B);
  const accent = Color(0xFFF39800);
  final surface = dark ? const Color(0xFF18232F) : Colors.white;
  final onPrimary = dark ? const Color(0xFF002C53) : Colors.white;
  final scheme = base.colorScheme.copyWith(
    primary: primary,
    onPrimary: onPrimary,
    secondary: accent,
    onSecondary: const Color(0xFF241A00),
    surface: surface,
    primaryContainer: dark ? const Color(0xFF163E62) : const Color(0xFFE8F2FC),
    onPrimaryContainer: dark ? Colors.white : primary,
  );
  final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(12));
  final border = OutlineInputBorder(
    borderRadius: BorderRadius.circular(6),
    borderSide: BorderSide(color: primary),
  );
  Color? selection(Set<WidgetState> states) {
    if (states.contains(WidgetState.disabled)) return null;
    return states.contains(WidgetState.selected) ? accent : scheme.outline;
  }

  return base.copyWith(
    colorScheme: scheme,
    scaffoldBackgroundColor: dark
        ? const Color(0xFF101820)
        : const Color(0xFFF6F6F6),
    appBarTheme: base.appBarTheme.copyWith(
      backgroundColor: dark ? const Color(0xFF06315A) : primary,
      foregroundColor: Colors.white,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      iconTheme: const IconThemeData(color: Colors.white),
      actionsIconTheme: const IconThemeData(color: Colors.white),
    ),
    cardTheme: base.cardTheme.copyWith(
      color: surface,
      surfaceTintColor: Colors.transparent,
      elevation: 2,
      shape: shape,
      margin: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    ),
    dialogTheme: base.dialogTheme.copyWith(
      backgroundColor: surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
      titleTextStyle: base.textTheme.titleLarge?.copyWith(
        color: scheme.onSurface,
      ),
    ),
    inputDecorationTheme: base.inputDecorationTheme.copyWith(
      filled: false,
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(
        borderSide: BorderSide(color: primary, width: 2),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: primary,
        foregroundColor: onPrimary,
        minimumSize: const Size(88, 48),
        shape: shape,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: primary,
        minimumSize: const Size(88, 48),
        side: BorderSide(color: primary, width: 1.5),
        shape: shape,
      ),
    ),
    floatingActionButtonTheme: base.floatingActionButtonTheme.copyWith(
      backgroundColor: accent,
      foregroundColor: const Color(0xFF241A00),
      shape: const CircleBorder(),
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(selection),
    ),
    checkboxTheme: base.checkboxTheme.copyWith(
      fillColor: WidgetStateProperty.resolveWith(
        (states) =>
            states.contains(WidgetState.selected) &&
                !states.contains(WidgetState.disabled)
            ? accent
            : null,
      ),
      checkColor: const WidgetStatePropertyAll(Color(0xFF241A00)),
    ),
    switchTheme: base.switchTheme.copyWith(
      thumbColor: WidgetStateProperty.resolveWith(selection),
      trackColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? accent.withValues(alpha: .25)
            : null,
      ),
    ),
    iconTheme: base.iconTheme.copyWith(color: primary),
    dataTableTheme: base.dataTableTheme.copyWith(
      headingRowColor: WidgetStatePropertyAll(
        dark ? const Color(0xFF06315A) : primary,
      ),
      headingTextStyle: base.textTheme.titleSmall?.copyWith(
        color: Colors.white,
      ),
    ),
  );
}
