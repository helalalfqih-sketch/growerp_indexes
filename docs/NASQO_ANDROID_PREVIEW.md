# NASQO ERP Android preview

- This preview is a **debug-signed beta APK**, not a Google Play release.
- Display name: **نسقو ERP**, version 1.22.6 (build 143).
- Connects exclusively to the production NASQO ERP backend at
  `https://erp.indexesstore.com` (do not use it with test tenant data).
- Existing Google-account sign-in uses a browser and a manual pairing code.
- Creating a new company via Google opens the secure website in a browser.
  After completing the signup and company setup, return to the app and
  choose *Sign in with Google*.
- The preview package ID is `org.growerp.admin.nasqo.preview`. This
  intentionally installs beside the legacy app, not over it.
- Do not distribute as an official production release or treat account and
  accounting workflows as fully certified. Signing for app store upgrades
  requires the organization's existing release keystore.
