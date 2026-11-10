#!/usr/bin/env python3
"""Synthetic-only tests of NASQO review candidates. No real customer data."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from nasqo_legacy_staging import MigrationBlocked
from nasqo_prepare_review_candidates import prepare_review
from test_nasqo_legacy_staging import synthetic_backup


class ReviewCandidatesTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="nasqo-candidate-test-")
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.source = self.root / "synthetic_legacy.back"
        synthetic_backup(self.source)
        self.source_sha = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.mapping_file = self.root / "mapping.json"
        self.mapping = {
            "sourceFileSha256": self.source_sha,
            "targetCompanyName": "Synthetic Test Store",
            "targetCompanyPartyIdCandidate": "900001",
            "targetCompanyPartyId": None,
            "targetCompanyVerified": False,
            "approved": False,
            "rules": {"postAccounting": False, "allowAutomaticImport": False},
        }
        self.save_mapping()

    def save_mapping(self):
        self.mapping_file.write_text(
            json.dumps(self.mapping, ensure_ascii=False),
            encoding="utf-8",
        )

    def test_metadata_only_does_not_expose_individual_rows(self):
        output = self.root / "metadata"
        report = prepare_review(self.source, self.mapping_file, output)
        self.assertEqual(report["sourceTableCount"], 9)
        self.assertEqual(report["sourceRows"], 11)
        self.assertEqual(report["sourceSummary"]["products"], 2)
        self.assertEqual(report["sourceSummary"]["parties"], 1)
        self.assertEqual(report["sourceSummary"]["locations"], 1)
        self.assertEqual(report["sourceSummary"]["financialMovements"], 2)
        self.assertEqual(report["reviewFindings"]["duplicateProductNameGroups"], 1)
        self.assertEqual(report["sourceSha256"], self.source_sha)
        self.assertEqual(
            set(x.name for x in output.iterdir()), {"review_manifest.json"}
        )
        self.assertEqual(report["targetDatabaseWrites"], 0)
        self.assertEqual(report["postedFinancialEntries"], 0)
        self.assertFalse(report["autoImportAllowed"])
        self.assertFalse(report["allRecordsMappedToNASQO"])
        self.assertNotIn("Synthetic buyer", (output / "review_manifest.json").read_text())

    def test_private_records_are_unapproved_and_nonpostable(self):
        output = self.root / "private"
        report = prepare_review(
            self.source, self.mapping_file, output, include_private_records=True
        )
        self.assertEqual(report["localCandidateCounts"], {
            "products": 2, "parties": 1, "locations": 1, "financial": 2,
        })
        products = [
            json.loads(line) for line in
            (output / "product_candidates.jsonl").read_text().splitlines()
        ]
        self.assertEqual(len(products), 2)
        self.assertTrue(all(p["status"] == "REVIEW_REQUIRED" for p in products))
        self.assertTrue(all(p["proposedNASQO"]["price"] is None for p in products))
        self.assertTrue(all(p["proposedNASQO"]["productId"] is None for p in products))
        self.assertTrue(all("VERIFY_TYPE_UNIT_CURRENCY_AND_PRICES" in p["reasons"]
                            for p in products))
        self.assertTrue(all("DUPLICATE_PRODUCT_NAME" in p["reasons"]
                            for p in products))
        self.assertEqual(products[0]["proposedNASQO"]["productName"], "Sample item")
        parties = [
            json.loads(line) for line in
            (output / "party_candidates.jsonl").read_text().splitlines()
        ]
        self.assertIsNone(parties[0]["proposedNASQO"]["role"])
        self.assertIsNone(parties[0]["proposedNASQO"]["telephoneNr"])
        self.assertEqual(parties[0]["source"]["name"], "Synthetic buyer")
        locations = [
            json.loads(line) for line in
            (output / "location_candidates.jsonl").read_text().splitlines()
        ]
        self.assertEqual(locations[0]["proposedNASQO"]["locationName"], "Demo store")
        self.assertIsNone(locations[0]["proposedNASQO"]["locationId"])
        finance = [
            json.loads(line) for line in
            (output / "financial_holds.jsonl").read_text().splitlines()
        ]
        self.assertEqual(len(finance), 2)
        self.assertTrue(all(f["status"] == "BLOCKED_FINANCIAL_MAPPING"
                            and f["proposedNASQO"]["isPosted"] is False
                            and f["proposedNASQO"]["grandTotal"] is None
                            and f["proposedNASQO"]["docType"] is None
                            for f in finance))
        self.assertEqual(finance[0]["mainLineCount"], 1)
        self.assertEqual(finance[1]["mainLineCount"], 0)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),
                         self.source_sha)

    def test_unverified_staging_target_cannot_be_assigned(self):
        self.mapping["targetCompanyPartyId"] = "100001"
        self.save_mapping()
        output = self.root / "rejected"
        with self.assertRaises(MigrationBlocked):
            prepare_review(self.source, self.mapping_file, output)
        self.assertFalse(output.exists())

    def test_mapping_hash_mismatch_blocks(self):
        self.mapping["sourceFileSha256"] = "0" * 64
        self.save_mapping()
        with self.assertRaises(MigrationBlocked):
            prepare_review(self.source, self.mapping_file, self.root / "rejected")
        self.assertFalse((self.root / "rejected").exists())

    def test_unsafe_autoimport_or_posting_blocks(self):
        for key in ("postAccounting", "allowAutomaticImport"):
            with self.subTest(key=key):
                self.mapping["rules"][key] = True
                self.save_mapping()
                with self.assertRaises(MigrationBlocked):
                    prepare_review(self.source, self.mapping_file, self.root / "invalid")
                self.assertFalse((self.root / "invalid").exists())
                self.mapping["rules"][key] = False

    def test_refuse_overwrite(self):
        output = self.root / "once"
        prepare_review(self.source, self.mapping_file, output)
        with self.assertRaises(MigrationBlocked):
            prepare_review(self.source, self.mapping_file, output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
