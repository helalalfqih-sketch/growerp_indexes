import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
from android_production_preflight import run


class AndroidPreflightTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.app = self.root / "flutter/packages/admin"
        (self.app / "android/app/src/main").mkdir(parents=True)
        (self.app / "assets/cfg").mkdir(parents=True)
        self.gradle('com.example.accounting')
        (self.app / "android/app/src/main/AndroidManifest.xml").write_text(
            '<application android:label="My Accounting"/>')
        (self.app / "pubspec.yaml").write_text("name: admin\nversion: 1.22.5+142\n")
        self.settings = {
            "databaseUrl": "https://api.example.org",
            "chatUrl": "wss://ws.example.org",
            "test": False,
            "restRequestLogs": False,
            "restResponseLogs": False,
        }
        self.write_settings()

    def gradle(self, app_id):
        (self.app / "android/app/build.gradle.kts").write_text(
            f'applicationId = "{app_id}"\n'
            'release { signingConfig = signingConfigs.getByName("release") }\n')

    def write_settings(self):
        (self.app / "assets/cfg/app_settings.json").write_text(json.dumps(self.settings))

    def test_statically_clean_configuration_passes(self):
        self.assertEqual(run(self.root), [])

    def test_detects_original_app_identity_and_backend(self):
        self.gradle("org.growerp.admin")
        self.settings["databaseUrl"] = "https://backend.growerp.com"
        self.settings["chatUrl"] = "wss://backend.growerp.com"
        self.write_settings()
        issues = run(self.root)
        self.assertTrue(any("UPSTREAM_APP_ID" in issue for issue in issues))
        self.assertEqual(sum("UNOWNED_BACKEND" in issue for issue in issues), 2)

    def test_checks_version_and_tls(self):
        (self.app / "pubspec.yaml").write_text("version: 1.22.4+141\n")
        self.settings["databaseUrl"] = "http://api.example.org"
        self.write_settings()
        issues = run(self.root)
        self.assertTrue(any("VERSION_CODE" in issue for issue in issues))
        self.assertTrue(any("INSECURE_URL" in issue for issue in issues))

    def test_signing_check_keeps_secrets_private(self):
        (self.app / "android/key.properties").write_text(
            "storeFile=/secret/not-here.jks\nkeyAlias=release\n"
            "storePassword=secret-pass\nkeyPassword=secret-pass\n")
        issues = " ".join(run(self.root, check_signing=True))
        self.assertIn("SIGNING_KEY", issues)
        self.assertNotIn("secret-pass", issues)
        self.assertNotIn("/secret/not-here.jks", issues)

    def test_flags_original_brand_and_deep_link(self):
        (self.app / "android/app/src/main/AndroidManifest.xml").write_text(
            '<application android:label="GrowERP Administrator"/>'
            '<data android:host="admin.growerp.com"/>')
        issues = run(self.root)
        self.assertTrue(any("UPSTREAM_BRAND" in issue for issue in issues))
        self.assertTrue(any("UPSTREAM_APPLINK" in issue for issue in issues))


if __name__ == "__main__":
    unittest.main()
