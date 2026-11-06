# Arabic accounting interface

This change adds Arabic resources to the existing GrowERP application. It keeps
the Flutter/Moqui architecture and the existing accounting and authorization
behavior. Select **العربية** from the language menu on the sign-in screen. The
choice is saved on the device and restored on the next launch.

## Scope

The following building blocks have complete Arabic ARB message coverage:

| Package | Interface |
| --- | --- |
| `growerp_core` | Shared controls, authentication and navigation labels |
| `growerp_order_accounting` | Accounting, orders, invoices and payments |
| `growerp_user_company` | Customers, suppliers, companies and users |
| `growerp_catalog` | Products, categories and subscriptions |
| `growerp_inventory` | Assets, locations and stock |
| `growerp_pos` | Checkout, tills and restaurant operations |
| `growerp_activity` | Tasks, events and time entries |

Flutter's Arabic locale supplies right-to-left direction and localized system
controls. Shared data tables use directional start/end alignment; explicitly
left/right aligned columns keep their specified meaning.

Other modules use their existing English resources until they have an Arabic
translation. `FallbackLocalizationsDelegate` preserves each module's resource
type and does not change the application's locale or text direction. Existing
translations, including German and Thai, continue to take precedence whenever
the underlying module supports them.

## Validation

From `flutter/`, with Flutter 3.47.9 and dependencies/code generation prepared:

```sh
python3 ci/check_arabic_localizations.py
dart run melos l10n --no-select
cd packages/growerp_core
flutter test test/arabic_localization_test.dart test/styled_data_table_rtl_test.dart
cd ../admin
flutter test test/arabic_delegates_test.dart
```

The coverage check requires an Arabic value for every English message in the
seven packages and matching ICU argument names. Flutter's generator validates
ICU syntax and argument types. Widget tests exercise the real application shell
and sign-in language menu at phone and desktop sizes, persistence across app
recreation, Arabic plural forms, RTL table alignment, and resource lookup for
all localization delegates registered by Admin.

The accounting widget regression also opens the Arabic tax-rate dialog,
validates required inputs, submits an Arabic name and decimal percentage to a
mocked REST client, and checks the resulting list entry. It does not replace a
test against a running backend.

### Cloud validation

The `Arabic localization` GitHub Actions workflow runs on relevant Flutter
changes in pull requests, pushes to `master`, and manual dispatch. It installs
the pinned Flutter SDK on the runner, generates the project sources, runs both
ARB checkers, analyzes the localization implementation and merged RTL tests,
and executes the eight package test suites. Results are uploaded as the
`arabic-localization-tests` artifact. Local Flutter, Gradle or Java installation
is not required to run this workflow.

This workflow produces test reports. It does not build an APK or deploy to app
stores. The separate `Publish Binary to Stores` workflow performs store uploads
and uses release credentials; an APK for direct testing needs a dedicated build
that uploads the APK as an artifact.

## Remaining product work

- Backend-provided data, including account names and dynamically configured menu
  text, require separate localization. ARB coverage is not full-screen coverage
  for hard-coded or server-provided text.
- Arabic PDF/receipt font embedding, shaping and printer output need dedicated
  implementation and tests. This change does not certify Arabic printing.
- All accounting screens still require visual review on target Android devices,
  including mixed Arabic/Latin identifiers, currencies and long labels.
- The application still uses its existing server. Offline storage and conflict
  resolution are a separate architectural change.
- No release identity, signing key, application ID or production endpoint is
  changed. Device authentication, financial workflows, Bluetooth printers and
  camera integration must be verified against a configured test backend before
  a release is considered.
