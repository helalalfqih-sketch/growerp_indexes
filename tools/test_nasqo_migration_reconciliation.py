#!/usr/bin/env python3
"""Synthetic-only tests for aggregate NASQO SQLite reconciliation."""
from __future__ import annotations

import hashlib
import sqlite3
import tempfile
import unittest
from pathlib import Path

from nasqo_migration_reconciliation import analyze_reconciliation
from test_nasqo_legacy_staging import synthetic_backup


class ReconcileTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="nasqo-reconciliation-test-")
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.source = self.base / "synthetic_legacy.back"
        synthetic_backup(self.source)
        self.before = hashlib.sha256(self.source.read_bytes()).hexdigest()

    def test_reconciliation_reports_unapproved_financial_codes(self):
        report = analyze_reconciliation(self.source)
        self.assertEqual(report["format"], "nasqo-legacy-reconciliation-v1")
        self.assertEqual(report["sourceHash"], self.before)
        self.assertEqual(report["totalTables"], 9)
        self.assertEqual(report["totalSourceRows"], 11)
        self.assertEqual(report["sourceFinance"]["movements"], 2)
        self.assertTrue(report["sourceFinance"]["billTypeRowCountReconciled"])
        self.assertEqual(
            report["sourceFinance"]["perTypeLineCoverage"], [
                {"billTypeUnclassified": 3, "count": 1, "withMainLines": 1,
                 "withSupplementaryLines": 0, "withoutEitherLineType": 0},
                {"billTypeUnclassified": 4, "count": 1, "withMainLines": 0,
                 "withSupplementaryLines": 0, "withoutEitherLineType": 1},
            ],
        )
        self.assertEqual(report["sourcePartyTypes"], {"0": 1})
        self.assertEqual(report["duplicateProductNameGroups"], 1)
        self.assertEqual(
            report["unmatchedReferences"]["input_move.cur -> currencies.id"], 1
        )
        self.assertEqual(
            report["unmatchedReferences"]["store_trans.store_id2 -> stores.id"], 1
        )
        self.assertTrue(report["dataCanBeStagedForReview"])
        self.assertFalse(report["dataCanBeImportedToProduction"])
        self.assertFalse(report["financialPostingAllowed"])
        self.assertFalse(report["accountingReconciliationApproved"])
        self.assertEqual(report["destinationWrites"], 0)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),
                         self.before)

    def test_with_extra_details_never_deems_finance_approved(self):
        db = sqlite3.connect(self.source)
        db.execute("CREATE TABLE bill_details2(id INTEGER PRIMARY KEY, bill_id INTEGER)")
        db.execute("INSERT INTO bill_details2 VALUES(102,11)")
        db.commit()
        db.close()
        report = analyze_reconciliation(self.source)
        self.assertEqual(report["sourceFinance"]["movementsWithMainDetails"], 1)
        self.assertEqual(report["sourceFinance"]["movementsWithSupplementaryDetails"], 1)
        self.assertEqual(report["sourceFinance"]["perTypeLineCoverage"][1][
            "withoutEitherLineType"], 0)
        self.assertFalse(report["financialPostingAllowed"])

    def test_source_is_unchanged_by_report(self):
        analyze_reconciliation(self.source)
        self.assertEqual(self.before, hashlib.sha256(self.source.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
