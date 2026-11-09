#!/usr/bin/env python3
"""Accounting-only legacy SQLite preview contract; synthetic data only.

This is intentionally a read-only API contract test, not a financial importer.
Unknown source movement codes must not be mapped to ledger debit/credit.
"""
import json
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "backend/service/NasqoSqliteImportUtil.groovy"
SERVICE = ROOT / "backend/service/growerp/100/NasqoSqliteImportServices100.xml"
MODEL = ROOT / "flutter/packages/growerp_models/lib/src/models/sqlite_import_preview_model.dart"
AR = ROOT / "flutter/packages/growerp_core/lib/l10n/core_ar.arb"
EN = ROOT / "flutter/packages/growerp_core/lib/l10n/core_en.arb"

class AccountingOnlyPreviewTests(unittest.TestCase):
    def test_service_remains_authenticated_read_only(self):
        root = ET.parse(SERVICE).getroot()
        services = root.findall("service")
        self.assertEqual(len(services), 1)
        s = services[0]
        self.assertEqual(s.get("verb"), "preview")
        self.assertEqual(s.get("noun"), "SqliteImport")
        self.assertEqual(s.get("authenticate"), "true")
        body = s.find("./actions/script").text
        self.assertIn("GROWERP_M_ADMIN", str(ET.tostring(s, encoding="unicode")))
        self.assertIn("temp.delete()", body)
        self.assertIn("new java.io.FileOutputStream(temp)", body)
        self.assertIn("preview = util.preview(temp)", body)
        self.assertNotIn("post#AcctgTrans", body)

    def test_source_scope_and_zero_posting(self):
        c = HELPER.read_text(encoding="utf-8")
        for key in ["financialMoves", "billDetails", "stockMoves", "storeTransfers"]:
            self.assertIn("counts." + key + " =", c)
        for table in ["bill_details2", "boxes", "cboxes", "macc"]:
            self.assertIn("'" + table + "'", c)
        self.assertIn("targetModule: 'accounting'", c)
        self.assertIn("productCatalogImport: false", c)
        self.assertIn("globalApplicationImport: false", c)
        self.assertIn("accountingPostingAllowed: false", c)
        self.assertIn("automaticLedgerConversionAllowed: false", c)
        self.assertIn("readyForAccountingImport: false", c)
        self.assertIn("postAccounting: false", c)
        self.assertIn("import java.sql.DriverManager", c)
        self.assertIn("PRAGMA query_only=ON", c)
        self.assertIn("mode=ro", c)
        self.assertNotIn("import groovy.sql.Sql", c)
        self.assertNotIn("acctgTransEntryId", c)
        self.assertNotIn("post#AcctgTrans", c)
        self.assertIn("sourcePartyTypes", c)
        self.assertIn("unmatchedMovementCurrencyRefs", c)
        # Financial direction must not be automatically inferred.
        self.assertNotIn("debitCreditFlag", c)
        self.assertNotIn("debitAmount", c)
        self.assertNotIn("creditAmount", c)
        self.assertIn("dain/madin", c)

    def test_flutter_parser_accepts_accounting_only_review(self):
        c = MODEL.read_text(encoding="utf-8")
        self.assertIn("final Map<String, dynamic> accountingReview;", c)
        self.assertIn("value['accountingReview']", c)
        self.assertIn("final Map<String, int> counts;", c)

    def test_accounting_labels_are_not_pos_specific(self):
        ar = json.loads(AR.read_text(encoding="utf-8"))
        en = json.loads(EN.read_text(encoding="utf-8"))
        self.assertIn("المحاسب الذكي برو", ar["sqliteImportInfo"])
        self.assertIn("محاسبة نسقو", ar["sqliteImportInfo"])
        self.assertIn("Smart Accountant Pro", en["sqliteImportInfo"])
        self.assertNotIn("POS", en["sqliteImportInfo"])

    def test_synthetic_sqlite_schema_has_document_currency_and_cashboxes(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / "synthetic_smart_accountant.back"
            with sqlite3.connect(source) as db:
                db.executescript("""
                    CREATE TABLE input_move(
                        id INTEGER PRIMARY KEY,bill_type INTEGER,bstate INTEGER,
                        per_id INTEGER,cur INTEGER,dain TEXT,madin TEXT
                    );
                    CREATE TABLE currencies(id INTEGER PRIMARY KEY,name TEXT);
                    CREATE TABLE customers(id INTEGER PRIMARY KEY,type INTEGER);
                    CREATE TABLE bill_details(id INTEGER PRIMARY KEY,bill_id INTEGER);
                    CREATE TABLE bill_details2(id INTEGER PRIMARY KEY,bill_id INTEGER);
                    CREATE TABLE boxes(id INTEGER PRIMARY KEY);
                    CREATE TABLE cboxes(id INTEGER PRIMARY KEY);
                    CREATE TABLE macc(id INTEGER PRIMARY KEY);
                    INSERT INTO currencies VALUES (1, 'Synthetic currency');
                    INSERT INTO input_move VALUES (10, 3, 1, 1, 1, '20', '20');
                    INSERT INTO input_move VALUES (11, 7, 2, 1, 9, '0', '5');
                    INSERT INTO customers VALUES(1, 4);
                    INSERT INTO bill_details VALUES(100, 10);
                    INSERT INTO bill_details2 VALUES(200, 11);
                    INSERT INTO boxes VALUES (1);
                    INSERT INTO cboxes VALUES (2);
                """)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM input_move m LEFT JOIN currencies c "
                    "ON c.id=m.cur WHERE m.cur IS NOT NULL AND c.id IS NULL"
                ).fetchone()[0], 1)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM boxes").fetchone()[0] +
                                 db.execute("SELECT COUNT(*) FROM cboxes").fetchone()[0], 2)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM macc").fetchone()[0], 0)
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")

if __name__ == "__main__":
    unittest.main(verbosity=2)
