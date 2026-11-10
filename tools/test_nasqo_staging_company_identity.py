#!/usr/bin/env python3
"""Synthetic-only tests for NASQO staging company identity gate."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from nasqo_staging_company_identity import (
    IdentityBlocked, normalized_name, run, validate_staging_host, verify_response,
)


def fixture():
    digest = hashlib.sha256(b"synthetic backup bytes").hexdigest()
    mapping = {
        "format": "nasqo-manual-mapping-template-v1",
        "targetCompanyName": "اندكس ستور",
        "targetCompanyAliases": ["Indexes Store"],
        "targetCompanyPartyId": None,
        "targetCompanyPartyIdCandidate": "900001",
        "targetCompanyVerified": False,
        "approved": False,
        "sourceFileSha256": digest,
        "rules": {"postAccounting": False, "allowAutomaticImport": False},
    }
    manifest = {
        "source": {"sha256": digest},
        "tables": {"products": {"rows": 2}, "input_move": {"rows": 1}},
    }
    return mapping, manifest


class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nasqo-identity-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mapping, self.manifest = fixture()
        self.mapping_path = self.root / "mapping.json"
        self.manifest_path = self.root / "manifest.json"
        self.mapping_path.write_text(
            json.dumps(self.mapping, ensure_ascii=False), encoding="utf-8"
        )
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_no_response_is_blocked_and_does_not_write(self):
        data = run(self.mapping_path, self.manifest_path)
        self.assertEqual(data["identityStatus"], "PENDING_AUTHENTICATED_STAGING_LOOKUP")
        self.assertFalse(data["stagingMigrationApproved"])
        self.assertEqual(data["targetRowsWritten"], 0)
        self.assertFalse(data["safeToPostAccounting"])
        self.assertEqual(set(x.name for x in self.root.iterdir()),
                         {"mapping.json", "manifest.json"})

    def test_offline_matching_response_is_not_live_verification(self):
        payload = {"companies": [
            {"partyId": "900001", "pseudoId": "42", "name": "Indexes Store"}
        ]}
        out = verify_response(self.mapping, self.manifest, payload)
        self.assertTrue(out["companyPartyIdMatched"])
        self.assertTrue(out["companyNameMatched"])
        self.assertEqual(out["identityStatus"], "MATCHED_UNTRUSTED_FILE_ONLY")
        self.assertFalse(out["authenticatedStagingResponse"])
        self.assertFalse(out["stagingMigrationApproved"])

    def test_pseudo_id_only_never_satisfies_party_id(self):
        payload = {"companies": [
            {"partyId": "900002", "pseudoId": "900001", "name": "اندكس ستور"}
        ]}
        out = verify_response(self.mapping, self.manifest, payload)
        self.assertEqual(out["identityStatus"], "BLOCKED_IDENTITY_MISMATCH")
        self.assertTrue(out["pseudoIdOnly"])
        self.assertFalse(out["companyPartyIdMatched"])

    def test_wrong_company_name_blocks_even_if_id_matches(self):
        payload = {"companies": [
            {"partyId": "900001", "pseudoId": "17", "name": "Another company"}
        ]}
        out = verify_response(self.mapping, self.manifest, payload,
                              authenticated_staging=True)
        self.assertEqual(out["identityStatus"], "BLOCKED_IDENTITY_MISMATCH")
        self.assertFalse(out["companyNameMatched"])

    def test_duplicate_company_rows_block(self):
        payload = {"companies": [
            {"partyId": "900001", "name": "اندكس ستور"},
            {"partyId": "900001", "name": "اندكس ستور"},
        ]}
        out = verify_response(self.mapping, self.manifest, payload)
        self.assertFalse(out["companyPartyIdMatched"])

    def test_authenticated_staging_check_never_approves_import(self):
        payload = {"companies": [
            {"partyId": "900001", "pseudoId": "1", "name": "اندكس ستور"}
        ]}
        out = verify_response(self.mapping, self.manifest, payload,
                              authenticated_staging=True)
        self.assertEqual(out["identityStatus"], "MATCHED_AUTHENTICATED_STAGING_ONLY")
        self.assertTrue(out["companyNameMatched"])
        self.assertFalse(out["safeToCreateFinancialDocuments"])
        self.assertFalse(out["safeToPostAccounting"])
        self.assertFalse(out["stagingMigrationApproved"])
        self.assertEqual(out["targetRowsWritten"], 0)

    def test_expected_aliases_and_unicode(self):
        self.assertEqual(normalized_name("أندكس سـتور"),
                         normalized_name("اندكس ستور"))

    def test_staging_hostname_required(self):
        for url in [
            "http://staging.example.test",
            "https://erp.indexesstore.com",
            "https://production.example.com",
            "https://staging.example.test/path",
            "https://alice:password@staging.example.test",
            "https://staging.example.test?token=bad",
        ]:
            with self.subTest(url=url):
                with self.assertRaises(IdentityBlocked):
                    validate_staging_host(url)
        self.assertEqual(
            validate_staging_host("https://staging.example.test"),
            "https://staging.example.test",
        )

    def test_mismatched_source_hash_is_blocked(self):
        self.mapping["sourceFileSha256"] = "bad"
        with self.assertRaises(IdentityBlocked):
            verify_response(self.mapping, self.manifest, {"companies": []})

    def test_claimed_verification_is_not_accepted(self):
        self.mapping["targetCompanyVerified"] = True
        self.mapping_path.write_text(
            json.dumps(self.mapping, ensure_ascii=False), encoding="utf-8"
        )
        with self.assertRaises(IdentityBlocked):
            run(self.mapping_path, self.manifest_path)

    def test_staging_api_dry_run_is_mock_only(self):
        with patch.dict("os.environ", {"NASQO_STAGING_API_KEY": "synthetic_value_123"}):
            with patch("nasqo_staging_company_identity.fetch_staging_company",
                       return_value={"companies": [
                           {"partyId": "900001", "name": "Indexes Store"}
                       ]}) as fake:
                out = run(
                    self.mapping_path, self.manifest_path,
                    base_url="https://staging.example.test"
                )
                self.assertEqual(out["identityStatus"],
                                 "MATCHED_AUTHENTICATED_STAGING_ONLY")
                fake.assert_called_once()
                self.assertEqual(out["targetRowsWritten"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
