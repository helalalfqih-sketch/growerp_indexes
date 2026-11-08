#!/usr/bin/env python3
"""Tests for the read-only legacy SQLite import report."""

import json
import sqlite3
import tempfile
from pathlib import Path

from import_nasqo_sqlite_backup import build_report


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "fixture.back"
        connection = sqlite3.connect(source)
        connection.executescript(
            """
            CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price TEXT,
                barcode TEXT, unit TEXT, price2 TEXT);
            CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT,
                phon TEXT, phone INTEGER, lim TEXT);
            CREATE TABLE stores (id INTEGER PRIMARY KEY, name TEXT, state INTEGER);
            CREATE TABLE currencies (id INTEGER PRIMARY KEY, name TEXT,
                sympol TEXT, amount TEXT, amount2 TEXT, name2 TEXT);
            CREATE TABLE input_move (id INTEGER PRIMARY KEY, bill_type INTEGER,
                bstate INTEGER, per_id INTEGER, dain TEXT, madin TEXT,
                year INTEGER, month INTEGER, day INTEGER);
            CREATE TABLE bill_details (id INTEGER PRIMARY KEY, bill_id INTEGER,
                prod_id INTEGER, price TEXT, amount INTEGER, total TEXT);
            CREATE TABLE bin (id INTEGER PRIMARY KEY);
            CREATE TABLE store_trans (id INTEGER PRIMARY KEY);
            INSERT INTO products VALUES (1, 'منتج', '10', '123', 'حبة', '7');
            INSERT INTO customers VALUES (1, 'عميل', '777', NULL, '0');
            INSERT INTO stores VALUES (1, 'المخزن', 1);
            INSERT INTO currencies VALUES (1, 'ريال', '_', '1', '1', '');
            INSERT INTO input_move VALUES (1, 3, 1, 1, '10', '10', 2026, 1, 1);
            INSERT INTO bill_details VALUES (1, 1, 1, '10', 1, '10');
            """
        )
        connection.commit()
        report = build_report(source, connection)
        connection.close()

    assert report["readOnly"] is True
    assert report["counts"]["products"] == 1
    assert report["counts"]["financialMoves"] == 1
    assert report["billTypes"] == [{"billType": 3, "state": 1, "count": 1}]
    assert report["importPolicy"]["postAccounting"] is False
    print("PASS: SQLite import report remains read-only and draft-only")


if __name__ == "__main__":
    main()