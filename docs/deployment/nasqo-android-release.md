# NASQO Android release packaging

NASQO Android release uses the permanent application ID `com.indexesstore.nasqo` and connects to `https://erp.indexesstore.com`. This is a separate install from the old version-specific debug preview ID; it is not an in-place update to that preview. The existing NASQO server and company data are unchanged.

The workflow pins Flutter 3.47.9, builds release APKs for arm64-v8a and x86_64 separately, checks the manifest/assets/alignment, and installs an isolated test-signed x86_64 copy on Android 13. It captures a screen and device logs, checks process survival and foreground activity, and verifies uninstall/reinstall.

## Signing and distribution

CI publishes **unsigned** APK artifacts for server-side signing. Never distribute those artifacts as installable downloads. `NASQO_BUILD_UNSIGNED=true` is an explicit CI-only opt-in; normal release builds require the configured release keystore.

The permanent NASQO signing key and its password are generated and retained in private server storage with restricted permissions. They must not be committed or uploaded to Actions artifacts. Each published APK must be signed with this same key, independently verified with apksigner, hashed after signing, and served under a new versioned filename. Never overwrite a file users may still be downloading.

Only the ARM64 release should be linked for supported ARM64 phones. The x86_64 artifact is for emulator testing.

## Verification limits

An Android 13 x86_64 emulator does not prove compatibility with a Samsung ARM64 device or identify the original phone-side installation failure. The release improves package size, stable identity and signing, but successful installation on the affected device must still be confirmed.

## Published release 1.22.9 (2026-11-10 Asia/Aden)

- Source commit: `a50950a281c688a340f824d0990636cf9f3febdb`; Android sources are unchanged by the later download-link and deployment-documentation commits.
- Successful build/test: [Actions run 42708701806](https://github.com/helalalfqih-sketch/growerp_indexes/actions/runs/42708701806).
- Android 13 x86_64 test: clean install, cold launch (`Status: ok`), foreground activity and process survival for 30 seconds, uninstall and reinstall all passed. The captured screen shows the NASQO welcome/login screen, version 1.22.9 build 146. This is not a completed Google sign-in test.
- Production ARM64 APK: [NASQO_ERP_Android_1.22.9_arm64.apk](https://erp.indexesstore.com/downloads/NASQO_ERP_Android_1.22.9_arm64.apk), **39,759,658 bytes** (about 39.8 MB versus 198.6 MB for the previous universal debug APK).
- APK SHA-256: `4c0bf3a00cec62076fd7086533a7a65a90127136f48ba810e4dcfa1f3a96a85f`.
- Public signing-certificate SHA-256: `72899f3befc6c319aa44b41b3ddd3303602709204440086af52dd31e0767685f`.
- Production APK signature schemes v2 and v3, ZIP integrity and 16 KB native-library alignment verified after signing. Public HTTP download matched the complete signed-file SHA-256 and size; range requests return 206 for resumable downloads.
- Only the current web image was recreated to update the download link. Backend and database container identities and existing application JavaScript hashes were unchanged. The old download was retained.

Use `tools/sign_nasqo_android_release.py` and `tools/publish_nasqo_android_release.py` as version-specific release procedures, with the expected CI run/source and prepared image checked before executing. Keep the private key and its password on the VPS. For the web-only image, use `COPY --chmod=644 index.html /usr/share/nginx/html/index.html` so Nginx can read the template. A failed public-page check automatically rolls back the web image; verify the restored page if rollback occurs.

Future releases must increase the version and use a new filename while retaining `com.indexesstore.nasqo` and the same signing key. The workflow can be started manually from the default branch. Installation on the affected Samsung phone remains unconfirmed.
