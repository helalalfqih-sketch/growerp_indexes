#!/usr/bin/env python3
"""Synthetic-only PostgreSQL rehearsal for NASQO's legacy SQLite conversion.

This does NOT connect to Moqui or restore a tenant. It creates a PostgreSQL
TEMP table, stages only row identifiers and SHA256 digests (not business data),
checks source coverage and accounting locks, then ROLLBACKs everything.

Safety boundaries: local PostgreSQL named nasqo_ci_disposable, explicit opt-in,
synthetic-only marker, no real financial records, no permanent schema.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
from contextlib import closing
from pathlib import Path
from urllib.parse import urlsplit

from nasqo_legacy_staging import REQUIRED_COLUMNS, open_source

EXPECTED_DATABASE = "nasqo_ci_disposable"
EXPECTED_USER = "nasqo_ci"
EXPECTED_MARKER = "NASQO_CI_SYNTHETIC_20261108"
OPT_IN_ENV = "NASQO_SYNTHETIC_REHEARSAL"
POSTGRES_ENV = "NASQO_CI_POSTGRES_DSN"
FINANCE_TABLES = {
    "input_move", "bill_details", "bill_details2", "bin",
    "bin_bill_details", "bin_bill_details2", "store_trans", "trans_details",
}


class UnsafeRehearsal(ValueError):
    pass


def require_local_synthetic_dsn(dsn: str) -> None:
    parsed = urlsplit(dsn)
    if (
        parsed.scheme != "postgresql"
        or parsed.hostname not in {"127.0.0.1", "localhost"}
        or parsed.port != 5432
        or parsed.path != "/" + EXPECTED_DATABASE
        or parsed.username != EXPECTED_USER
        or parsed.query or parsed.fragment
    ):
        raise UnsafeRehearsal(
            "Only the local synthetic nasqo_ci_disposable PostgreSQL service is allowed"
        )


def source_table_names(db: sqlite3.Connection) -> list[str]:
    return [
        name for (name,) in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]


def inspect_synthetic_source(path: Path) -> tuple[str, int, list[str]]:
    if not path.name.startswith("synthetic_"):
        raise UnsafeRehearsal("Rehearsal source must be a synthetic fixture")
    with closing(open_source(path)) as db:
        tables = source_table_names(db)
        if "nasqo_synthetic_fixture" not in tables:
            raise UnsafeRehearsal("Synthetic-only fixture marker is missing")
        marker = db.execute(
            "SELECT marker FROM nasqo_synthetic_fixture"
        ).fetchall()
        if len(marker) != 1 or marker[0][0] != EXPECTED_MARKER:
            raise UnsafeRehearsal("Synthetic fixture marker is invalid")
        if not REQUIRED_COLUMNS.keys() <= set(tables):
            raise UnsafeRehearsal("Required legacy tables are missing")
        selected = [t for t in tables if t != "nasqo_synthetic_fixture"]
        total = 0
        for table in selected:
            table_quoted = '"' + table.replace('"', '""') + '"'
            total += db.execute(
                "SELECT COUNT(*) FROM " + table_quoted
            ).fetchone()[0]
        if not 1 <= total <= 1000:
            raise UnsafeRehearsal("Rehearsal source exceeds synthetic size bound")

    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(part)
    return sha.hexdigest(), total, selected


def json_serialize(value):
    if isinstance(value, bytes):
        return {"sqliteBlobSHA256": hashlib.sha256(value).hexdigest()}
    raise TypeError("Unexpected SQLite value type")


def stage_and_rollback(source: Path, dsn: str) -> dict:
    if os.environ.get(OPT_IN_ENV) != "1":
        raise UnsafeRehearsal("Explicit synthetic rehearsal opt-in required")
    require_local_synthetic_dsn(dsn)
    source_sha, source_total, names = inspect_synthetic_source(source)

    # psycopg only needed when a disposable PostgreSQL service actually runs.
    import psycopg

    connection = psycopg.connect(dsn, connect_timeout=8)
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT current_database(),current_user")
            database_name, database_user = cur.fetchone()
            if database_name != EXPECTED_DATABASE or database_user != EXPECTED_USER:
                raise UnsafeRehearsal("PostgreSQL staging identity differs from CI")
            cur.execute("""
                CREATE TEMPORARY TABLE nasqo_review_candidates (
                    source_sha256 TEXT NOT NULL,
                    source_table TEXT NOT NULL,
                    source_rowid BIGINT NOT NULL,
                    value_sha256 TEXT NOT NULL,
                    review_status TEXT NOT NULL,
                    staging_company TEXT NOT NULL
                        CHECK (staging_company = 'SYNTHETIC_TENANT_900001'),
                    target_entity_id TEXT NULL CHECK (target_entity_id IS NULL),
                    accounting_posted BOOLEAN NOT NULL DEFAULT FALSE
                        CHECK (accounting_posted = FALSE),
                    PRIMARY KEY (source_sha256, source_table, source_rowid)
                ) ON COMMIT DROP
            """)
            with closing(open_source(source)) as db:
                for table in names:
                    quoted = '"' + table.replace('"', '""') + '"'
                    rows = db.execute("SELECT rowid,* FROM " + quoted)
                    status = (
                        "BLOCKED_FINANCIAL_MAPPING"
                        if table in FINANCE_TABLES
                        else "UNREVIEWED"
                    )
                    batch = []
                    for row in rows:
                        # Only hash raw values; no names, phone, prices or financial
                        # amounts are sent to the temporary PostgreSQL table.
                        payload = json.dumps(
                            list(row)[1:], ensure_ascii=False,
                            separators=(",", ":"), default=json_serialize,
                        )
                        batch.append((
                            source_sha, table, int(row[0]),
                            hashlib.sha256(payload.encode("utf-8")).hexdigest(),
                            status, "SYNTHETIC_TENANT_900001",
                        ))
                    if batch:
                        cur.executemany("""
                            INSERT INTO nasqo_review_candidates
                            (source_sha256,source_table,source_rowid,value_sha256,
                             review_status,staging_company)
                            VALUES (%s,%s,%s,%s,%s,%s)
                        """, batch)
            cur.execute("""
                SELECT COUNT(*),COUNT(DISTINCT source_table),
                       COUNT(*) FILTER (WHERE accounting_posted),
                       COUNT(*) FILTER (WHERE target_entity_id IS NOT NULL),
                       COUNT(*) FILTER (
                           WHERE review_status = 'BLOCKED_FINANCIAL_MAPPING')
                FROM nasqo_review_candidates
            """)
            count, table_count, posted, linked, blocked = cur.fetchone()
            if (count != source_total or table_count != len(names)
                    or posted != 0 or linked != 0 or blocked < 1):
                raise UnsafeRehearsal("Synthetic rehearsal invariants failed")
        # Explicit ROLLBACK: not even the temporary table remains.
        connection.rollback()
        with connection.cursor() as cur:
            cur.execute("SELECT to_regclass('pg_temp.nasqo_review_candidates')")
            if cur.fetchone()[0] is not None:
                raise UnsafeRehearsal("Temporary review rows remained after rollback")
            cur.execute("SELECT to_regclass('public.nasqo_review_candidates')")
            if cur.fetchone()[0] is not None:
                raise UnsafeRehearsal("A persistent review table exists unexpectedly")
        connection.rollback()
    finally:
        connection.close()

    return {
        "mode": "SYNTHETIC_ONLY",
        "status": "EPHEMERAL_POSTGRES_REHEARSAL_PASSED",
        "database": EXPECTED_DATABASE,
        "sourceTablesCovered": len(names),
        "syntheticRowsChecked": source_total,
        "financialCandidatesBlocked": blocked,
        "postedAccountingRows": 0,
        "linkedRealCompanyRows": 0,
        "persistentRowsWritten": 0,
        "moquiIntegrationTested": False,
        "realBackupTransferred": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    result = stage_and_rollback(
        source, os.environ.get(POSTGRES_ENV, "")
    )
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
