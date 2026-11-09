#!/usr/bin/env python3
"""Regression checks for the opt-in, per-company SQLite source staging path.

CI uses only source contracts; the real Groovy/JDBC + POSIX operation is tested
against synthetic SQLite inside the currently authorized NASQO backend runtime.
"""
import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "backend/service/NasqoSqliteImportUtil.groovy"
SERVICE = ROOT / "backend/service/growerp/100/NasqoSqliteImportServices100.xml"

class AccountingSourceStageContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = HELPER.read_text(encoding="utf-8")
        cls.service = ET.parse(SERVICE).getroot().find("service")
        cls.action = cls.service.find("./actions/script").text

    def test_authenticated_admin_only_and_company_scoped(self):
        self.assertEqual(self.service.get("authenticate"), "true")
        service_xml = ET.tostring(self.service, encoding="unicode")
        self.assertIn("GROWERP_M_ADMIN", service_xml)
        self.assertIn("get#RelatedCompanyAndOwner", service_xml)
        self.assertIn("companyPartyId?.toString() == stagingCompany", self.action)
        self.assertIn("System.getenv('NASQO_ACCOUNTING_STAGE_COMPANY')", self.action)
        self.assertIn("if (stagingCompany &&", self.action)

    def test_existing_preview_stays_read_only_and_deletes_temp(self):
        self.assertIn("preview = util.preview(temp)", self.action)
        self.assertIn("temp.delete()", self.action)
        self.assertIn("file.inputStream.withCloseable", self.action)
        self.assertIn("new java.io.FileOutputStream(temp)", self.action)
        self.assertIn("preview.accountingStage", self.action)
        self.assertIn("sourceRowsWrittenToAccounting: 0", self.action)
        self.assertNotIn("insert#", self.action)
        self.assertNotIn("update#", self.action)
        self.assertNotIn("post#AcctgTrans", self.action)
        self.assertNotIn("TRUNCATE", self.action)

    def test_stage_uses_durable_private_mount_and_strict_storage(self):
        for exact in [
            "static Map stageForAccounting(",
            "'/var/lib/nasqo-accounting-imports'",
            "Files.setPosixFilePermissions",
            "'rwx------'",
            "'r--------'",
            "MessageDigest.getInstance('SHA-256')",
            "source.length() > 32L * 1024L * 1024L",
            "Files.isSymbolicLink",
            "Files.createTempDirectory",
            "StandardCopyOption.ATOMIC_MOVE",
            "sourceRowsWrittenToAccounting: 0",
            "postedEntries: 0",
            "postAccounting: false",
            "applicationWideImport: false",
            "BLOCKED_PENDING_ACCOUNT_MAPPING",
        ]:
            self.assertIn(exact, self.helper + self.action, exact)
        self.assertIn("previouslyStaged.length > 0", self.helper)
        self.assertIn("storedSha != sha", self.helper)
        self.assertIn("reviewed.counts", self.helper)
        self.assertIn("reviewed.billTypes", self.helper)

    def test_never_post_to_ledger_or_import_customer_catalog(self):
        self.assertNotIn("jdbc:postgresql:", self.helper)
        self.assertNotIn("post#AcctgTrans", self.helper)
        self.assertNotIn("create#Invoice", self.helper)
        self.assertNotIn("create#Payment", self.helper)
        self.assertNotIn("create#GlAccount", self.helper)
        self.assertNotIn("ec.entity", self.helper)
        self.assertIn("return NasqoSqliteImportPreviewHelper", self.helper)
        # PII and raw transactions stay only in private source.back, not manifest.
        part = self.helper.split("Map manifest = [", 1)[1].split("Path manifestPath =", 1)[0]
        self.assertNotIn("customerName", part)
        self.assertNotIn("phone", part)
        self.assertNotIn("rawDain", part)
        self.assertNotIn("rawMadin", part)
        self.assertNotIn("sourceRows", part.replace("sourceRowsWrittenToAccounting", ""))

if __name__ == "__main__":
    unittest.main()
