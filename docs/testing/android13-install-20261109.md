# Android 13 public APK installation diagnostic — 2026-11-09

Public artifact: https://erp.indexesstore.com/downloads/NASQO_ERP_Android_1.22.8.apk

- APK size: 198643505 bytes.
- SHA-256: 54b9bc84db4aaba9ddd65d545c143fa6c7748ab3bdeefedb3a95b07c6f111d18.
- Package: org.growerp.admin.nasqo.preview.v1228; version 1.22.8 (145).
- API 33 / Android 13, clean Google APIs x86_64 emulator.
- Fresh installation: Success.
- Uninstall on disposable emulator: Success.
- Reinstallation of identical APK: Success.
- APK signature v2, archive integrity and alignment checks passed separately.
- Public site's download link points to this exact artifact.

Run: https://github.com/helalalfqih-sketch/growerp_indexes/actions/runs/42698419713

## Limits

This establishes installation and reinstallation on the emulator, not successful installation on a Samsung ARM64 phone. It does not reproduce the phone's invalid-package error.

The launch probe created a process, but am start -W reported Status: timeout after 10814 ms. Successful rendering, login and application functionality are not established by this test. The original job marker included LAUNCH; the script marker has been corrected to describe installation only.

No production application, signing key or APK was changed. Next useful evidence is the exact filename and full size/hash of the APK on the affected phone; if the identical file still fails, obtain the Android Package Manager installation error from that device before selecting a fix.
