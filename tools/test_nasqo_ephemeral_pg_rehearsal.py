#!/usr/bin/env python3
"""CI regression tests: fresh synthetic SQLite to disposable PostgreSQL TEMP table."""
from __future__ import annotations

import hashlib
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from nasqo_ephemeral_pg_rehearsal import (
    EXPECTED_MARKER, UnsafeRehearsal, require_local_synthetic_dsn,
    stage_and_rollback,
)
from test_nasqo_legacy_staging import synthetic_backup


class SyntheticPostgresRehearsalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nasqo-ci-pg-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "synthetic_legacy.back"
        synthetic_backup(self.source)
        self.dsn = os.environ.get("NASQO_CI_POSTGRES_DSN", "")

    def add_marker(self):
        db = sqlite3.connect(self.source)
        db.execute("CREATE TABLE nasqo_synthetic_fixture(marker TEXT NOT NULL)")
        db.execute(
            "INSERT INTO nasqo_synthetic_fixture(marker) VALUES (?)",
            (EXPECTED_MARKER,),
        )
        db.commit()
        db.close()

    def test_01_transactional_postgres_rehearsal_never_persists(self):
        self.add_marker()
        before = hashlib.sha256(self.source.read_bytes()).hexdigest()
        with patch.dict(os.environ, {"NASQO_SYNTHETIC_REHEARSAL": "1"}):
            result = stage_and_rollback(self.source, self.dsn)
        self.assertEqual(result["status"], "EPHEMERAL_POSTGRES_REHEARSAL_PASSED")
        self.assertEqual(result["sourceTablesCovered"], 9)
        self.assertEqual(result["syntheticRowsChecked"], 11)
        self.assertEqual(result["financialCandidatesBlocked"], 5)
        self.assertEqual(result["persistentRowsWritten"], 0)
        self.assertEqual(result["postedAccountingRows"], 0)
        self.assertEqual(result["linkedRealCompanyRows"], 0)
        self.assertFalse(result["moquiIntegrationTested"])
        self.assertFalse(result["realBackupTransferred"])
        self.assertEqual(
            hashlib.sha256(self.source.read_bytes()).hexdigest(), before
        )

    def test_02_prod_hosts_and_databases_rejected(self):
        examples = [
            "postgresql://nasqo_ci:password@db.production.com:5432/nasqo_ci_disposable",
            "postgresql://nasqo_ci:password@127.0.0.1:5432/nasqo",
            "postgresql://nasqo_ci:password@127.0.0.1:5432/postgres",
            "postgresql://postgres:password@127.0.0.1:5432/nasqo_ci_disposable",
            "postgresql://nasqo_ci:password@127.0.0.1:5434/nasqo_ci_disposable",
            "postgresql://nasqo_ci:password@127.0.0.1:5432/nasqo_ci_disposable?sslmode=disable",
        ]
        for dsn in examples:
            with self.subTest(dsn=dsn.split("@")[-1]):
                with self.assertRaises(UnsafeRehearsal):
                    require_local_synthetic_dsn(dsn)

    def test_03_missing_synthetic_marker_blocks_database_access(self):
        with patch.dict(os.environ, {"NASQO_SYNTHETIC_REHEARSAL": "1"}):
            with self.assertRaises(UnsafeRehearsal):
                stage_and_rollback(self.source, self.dsn)

    def test_04_explicit_opt_in_required(self):
        self.add_marker()
        with patch.dict(os.environ, {"NASQO_SYNTHETIC_REHEARSAL": ""}):
            with self.assertRaises(UnsafeRehearsal):
                stage_and_rollback(self.source, self.dsn)

    def test_05_bad_marker_blocks(self):
        self.add_marker()
        db = sqlite3.connect(self.source)
        db.execute(
            "UPDATE nasqo_synthetic_fixture SET marker='wrong-marker'"
        )
        db.commit()
        db.close()
        with patch.dict(os.environ, {"NASQO_SYNTHETIC_REHEARSAL": "1"}):
            with self.assertRaises(UnsafeRehearsal):
                stage_and_rollback(self.source, self.dsn)

    def test_06_original_backup_name_rejected(self):
        self.add_marker()
        renamed = self.root / "backup_2026.11.08_03.49.26.back"
        self.source.rename(renamed)
        with patch.dict(os.environ, {"NASQO_SYNTHETIC_REHEARSAL": "1"}):
            with self.assertRaises(UnsafeRehearsal):
                stage_and_rollback(renamed, self.dsn)


if __name__ == "__main__":
    unittest.main(verbosity=2)
