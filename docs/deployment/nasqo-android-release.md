# NASQO Android release packaging

NASQO Android release uses the permanent application ID `com.indexesstore.nasqo` and connects to `https://erp.indexesstore.com`. This is a separate install from the old version-specific debug preview ID; it is not an in-place update to that preview. The existing NASQO server and company data are unchanged.

The workflow pins Flutter 3.47.9, builds release APKs for arm64-v8a and x86_64 separately, checks the manifest/assets/alignment, and installs an isolated test-signed x86_64 copy on Android 13. It captures a screen and device logs, checks process survival and foreground activity, and verifies uninstall/reinstall.

## Signing and distribution

CI publishes **unsigned** APK artifacts for server-side signing. Never distribute those artifacts as installable downloads. `NASQO_BUILD_UNSIGNED=true` is an explicit CI-only opt-in; normal release builds require the configured release keystore.

The permanent NASQO signing key and its password are generated and retained in private server storage with restricted permissions. They must not be committed or uploaded to Actions artifacts. Each published APK must be signed with this same key, independently verified with apksigner, hashed after signing, and served under a new versioned filename. Never overwrite a file users may still be downloading.

Only the ARM64 release should be linked for supported ARM64 phones. The x86_64 artifact is for emulator testing.

## Verification limits

An Android 13 x86_64 emulator does not prove compatibility with a Samsung ARM64 device or identify the original phone-side installation failure. The release improves package size, stable identity and signing, but successful installation on the affected device must still be confirmed.
