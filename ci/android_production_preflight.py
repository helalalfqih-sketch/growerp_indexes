#!/usr/bin/env python3
"""Fail-closed static preflight for an independent GrowERP Android release.

This is not a substitute for backend/device tests, security and privacy review,
Play Console policy verification, or inspecting a signed release artifact.
It never displays keystore passwords, token values or local keystore paths.
"""

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlparse

UPSTREAM_APP_ID = "org.growerp.admin"
UPSTREAM_DOMAINS = {"backend.growerp.com"}
REQUIRED_SIGNING_KEYS = ("storeFile", "keyAlias", "keyPassword", "storePassword")


def _read(path):
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def _settings(root, errors):
    content = _read(root / "flutter/packages/admin/assets/cfg/app_settings.json")
    if content is None:
        errors.append("MISSING_SETTINGS: admin app_settings.json is unavailable")
        return
    try:
        settings = json.loads(content)
    except ValueError:
        errors.append("INVALID_SETTINGS: admin app_settings.json is not valid JSON")
        return
    for key, scheme in (("databaseUrl", "https"), ("chatUrl", "wss")):
        value = settings.get(key)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"MISSING_URL: {key} needs an approved production endpoint")
            continue
        url = urlparse(value)
        if url.scheme != scheme or not url.hostname:
            errors.append(f"INSECURE_URL: {key} must use {scheme} and a hostname")
        elif url.hostname in UPSTREAM_DOMAINS or url.hostname in {"localhost", "127.0.0.1"}:
            errors.append(f"UNOWNED_BACKEND: {key} is not an independent production service")
    for key in ("test", "restRequestLogs", "restResponseLogs"):
        if settings.get(key) is not False:
            errors.append(f"RELEASE_CONFIG: {key} must be explicitly false")


def _identity(root, errors, min_version_code):
    gradle = _read(root / "flutter/packages/admin/android/app/build.gradle.kts")
    if gradle is None:
        errors.append("MISSING_GRADLE: Android build.gradle.kts is unavailable")
    else:
        ids = re.findall(r'^\s*applicationId\s*=\s*"([^"]+)"', gradle, re.M)
        if len(ids) != 1:
            errors.append("APP_ID: expected exactly one explicit applicationId")
        elif ids[0] == UPSTREAM_APP_ID:
            errors.append("UPSTREAM_APP_ID: choose your own permanent package identifier")
        if 'signingConfig = signingConfigs.getByName("release")' not in gradle:
            errors.append("SIGNING_CONFIG: release build lacks its release signing config")
    manifest = _read(root / "flutter/packages/admin/android/app/src/main/AndroidManifest.xml")
    if manifest is None:
        errors.append("MISSING_MANIFEST: AndroidManifest.xml is unavailable")
    else:
        if 'android:label="GrowERP Administrator"' in manifest:
            errors.append("UPSTREAM_BRAND: set the intended app name")
        if re.search(r'android:host="(?:www\.)?admin\.growerp\.(?:com|org)"', manifest):
            errors.append("UPSTREAM_APPLINK: replace original GrowERP app-link domains")
    pubspec = _read(root / "flutter/packages/admin/pubspec.yaml")
    if pubspec is None:
        errors.append("MISSING_PUBSPEC: admin pubspec.yaml is unavailable")
    else:
        match = re.search(r"^version:\s*[^\s+]+\+(\d+)\s*$", pubspec, re.M)
        if not match:
            errors.append("VERSION_CODE: version must include numeric +build code")
        elif int(match.group(1)) <= min_version_code:
            errors.append(f"VERSION_CODE: versionCode must exceed {min_version_code}")


def _signing(root, errors):
    android = root / "flutter/packages/admin/android"
    content = _read(android / "key.properties")
    if content is None:
        errors.append("SIGNING_KEY: local android/key.properties unavailable; do NOT commit it")
        return
    props = {}
    for line in content.splitlines():
        stripped = line.strip()
        if "=" in stripped and not stripped.startswith(("#", "!")):
            key, value = stripped.split("=", 1)
            props[key.strip()] = value.strip()
    if not all(props.get(key) for key in REQUIRED_SIGNING_KEYS):
        errors.append("SIGNING_KEY: signing properties incomplete (details hidden)")
        return
    store = Path(props["storeFile"])
    if not store.is_absolute():
        store = android / "app" / store
    if not store.is_file():
        errors.append("SIGNING_KEY: keystore file not found (path hidden)")


def run(root, min_version_code=141, check_signing=False):
    errors = []
    _settings(root, errors)
    _identity(root, errors, min_version_code)
    if check_signing:
        _signing(root, errors)
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--min-version-code", type=int, default=141,
                        help="Set to verified maximum version code from your Play Console")
    parser.add_argument("--check-signing", action="store_true",
                        help="Check local release keystore without printing its secrets")
    parser.add_argument("--strict", action="store_true",
                        help="Exit with failure if any static checks are blocked")
    args = parser.parse_args()
    issues = run(args.root, args.min_version_code, args.check_signing)
    print("Independent Android release preflight (NOT a release approval)")
    for issue in issues:
        print(f"BLOCKED: {issue}")
    if not issues:
        print("Static checks passed; further real-world verification still required")
    if not args.check_signing:
        print("NOT_CHECKED: local keystore and signed APK/AAB integrity")
    print("NOT_CHECKED: backend flows, Shopify write sync, privacy, physical devices, Play Console")
    if issues and args.strict:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
