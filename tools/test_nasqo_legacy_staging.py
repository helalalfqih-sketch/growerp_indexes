#!/usr/bin/env python3
"""Synthetic-only regression tests for fail-closed NASQO legacy staging."""

import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from nasqo_legacy_staging import MigrationBlocked, create_private_bundle


def synthetic_backup(path: Path):
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE products(
          id INTEGER PRIMARY KEY, name TEXT, barcode TEXT, price TEXT,
          price2 TEXT, unit TEXT, cur INTEGER
        );
        CREATE TABLE customers(
          id INTEGER PRIMARY KEY, name TEXT, type INTEGER, phon TEXT
        );
        CREATE TABLE stores(
          id INTEGER PRIMARY KEY, name TEXT
        );
        CREATE TABLE currencies(
          id INTEGER PRIMARY KEY, name TEXT
        );
        CREATE TABLE input_move(
          id INTEGER PRIMARY KEY, bill_type INTEGER, bstate INTEGER,
          per_id INTEGER, dain TEXT, madin TEXT, cur INTEGER,
          year INTEGER, month INTEGER, day INTEGER,
          FOREIGN KEY(per_id) REFERENCES customers(id)
        );
        CREATE TABLE bill_details(
          id INTEGER PRIMARY KEY, bill_id INTEGER, prod_id INTEGER,
          store_id INTEGER, FOREIGN KEY(bill_id) REFERENCES input_move(id),
          FOREIGN KEY(prod_id) REFERENCES products(id)
        );
        CREATE TABLE bin (id INTEGER, note TEXT);
        CREATE TABLE store_trans(
          id INTEGER PRIMARY KEY, store_id1 INTEGER, store_id2 INTEGER
        );
        CREATE TABLE offers (id INTEGER PRIMARY KEY, notes TEXT);
        INSERT INTO products VALUES(1,'Sample item','A1','10.20','7.1','piece',1);
        INSERT INTO products VALUES(2,'Sample item','A2','12.00','8.0','piece',1);
        INSERT INTO customers VALUES(1,'Synthetic buyer',0,'000000');
        INSERT INTO stores VALUES(1,'Demo store');
        INSERT INTO currencies VALUES(1,'Test currency');
        INSERT INTO input_move VALUES(10,3,1,1,'10','10',9,2026,11,8);
        INSERT INTO input_move VALUES(11,4,1,1,'20','0',1,2026,11,8);
        INSERT INTO bill_details VALUES(100,10,1,1);
        INSERT INTO bin VALUES(99,'synthetic archive record');
        INSERT INTO store_trans VALUES(7,1,999);
        INSERT INTO offers VALUES(2,'synthetic quote');
    """)
    db.commit()
    db.close()


class SafeStagingTests(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix="nasqo-migrate-test-")
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name)
        self.backup = self.root / "legacy.back"
        synthetic_backup(self.backup)
        self.source_digest = hashlib.sha256(self.backup.read_bytes()).hexdigest()

    def test_manifest_has_all_tables_and_blocks_posting(self):
        destination = self.root / "review"
        result = create_private_bundle(self.backup, destination)
        self.assertEqual(result["approvalStatus"], "BLOCKED_PENDING_MANUAL_MAPPING")
        self.assertEqual(result["checks"]["sqliteIntegrity"], "ok")
        self.assertEqual(result["tableCount"], 9)
        self.assertEqual(result["totalRows"], 10)
        self.assertEqual(result["checks"]["rawDainEqualsRawMadinRows"], 1)
        self.assertEqual(result["checks"]["missingReferencedRows"][
            "input_move.cur -> currencies.id"], 1)
        self.assertEqual(result["checks"]["missingReferencedRows"][
            "store_trans.store_id2 -> stores.id"], 1)
        self.assertEqual(result["checks"]["duplicateProductNameGroups"], 1)
        self.assertFalse(result["financialPostingAllowed"])
        self.assertFalse(result["targetDatabaseModified"])
        self.assertEqual(result["source"]["sha256"], self.source_digest)
        self.assertEqual(result["tables"]["offers"]["rows"], 1)
        self.assertFalse((destination / "source_tables").exists())
        self.assertEqual(hashlib.sha256(self.backup.read_bytes()).hexdigest(),
                         self.source_digest)
        mapping = json.loads((destination / "mapping_template.json").read_text())
        self.assertFalse(mapping["approved"])
        self.assertIsNone(mapping["targetCompanyPartyId"])
        self.assertIsNone(mapping["sourceBillTypeToNasqoDocumentType"]["3"])
        self.assertIsNone(mapping["sourceBillTypeToNasqoDocumentType"]["4"])
        self.assertFalse(mapping["rules"]["allowAutomaticImport"])

    def test_private_record_staging_preserves_every_table_as_pending(self):
        destination = self.root / "private-review"
        result = create_private_bundle(self.backup, destination,
                                       stage_records=True)
        saved = json.loads((destination / "manifest.json").read_text())
        self.assertEqual(saved["tableCount"], len(saved["tables"]))
        for table, info in saved["tables"].items():
            relative = info["localSnapshot"]
            raw_path = destination / relative
            self.assertTrue(raw_path.is_file(), table)
            self.assertEqual(
                sum(1 for _ in raw_path.open(encoding="utf-8")), info["rows"]
            )
        products = [
            json.loads(line) for line in
            (destination / "product_candidates.jsonl").read_text().splitlines()
        ]
        self.assertEqual(len(products), 2)
        self.assertTrue(all(x["targetProductId"] is None for x in products))
        financial = [
            json.loads(line) for line in
            (destination / "financial_candidates.jsonl").read_text().splitlines()
        ]
        self.assertEqual(len(financial), 2)
        self.assertTrue(all(x["status"] == "BLOCKED_PENDING_MAPPING"
                            and x["posted"] is False
                            and x["targetFinancialDocumentId"] is None
                            for x in financial))
        self.assertEqual(hashlib.sha256(self.backup.read_bytes()).hexdigest(),
                         self.source_digest)
        self.assertEqual(result["totalRows"], 10)

    def test_never_overwrites_a_staging_bundle(self):
        destination = self.root / "review"
        create_private_bundle(self.backup, destination)
        with self.assertRaises(MigrationBlocked):
            create_private_bundle(self.backup, destination)

    def test_invalid_backup_is_rejected_without_output(self):
        broken = self.root / "broken.back"
        broken.write_bytes(b"something else" * 10)
        destination = self.root / "broken-review"
        with self.assertRaises(MigrationBlocked):
            create_private_bundle(broken, destination)
        self.assertFalse(destination.exists())

    def test_missing_mandatory_columns_are_rejected(self):
        missing = self.root / "bad-schema.back"
        db = sqlite3.connect(missing)
        db.executescript("""
            CREATE TABLE products(id INTEGER, name TEXT);
            CREATE TABLE customers(id INTEGER, name TEXT, type INTEGER);
            CREATE TABLE stores(id INTEGER, name TEXT);
            CREATE TABLE currencies(id INTEGER, name TEXT);
            CREATE TABLE input_move(id INTEGER, bill_type INTEGER,
                bstate INTEGER, per_id INTEGER, dain TEXT, madin TEXT);
            CREATE TABLE bill_details(id INTEGER, bill_id INTEGER, prod_id INTEGER);
            CREATE TABLE bin(id INTEGER);
            CREATE TABLE store_trans(id INTEGER, store_id1 INTEGER, store_id2 INTEGER);
        """)
        db.commit()
        db.close()
        destination = self.root / "bad-schema-review"
        with self.assertRaises(MigrationBlocked):
            create_private_bundle(missing, destination)
        self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
