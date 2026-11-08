#!/usr/bin/env python3
"""Create a reviewable NASQO import report from a legacy SQLite POS backup.

The source database is opened read-only. This tool deliberately produces a
report and normalized JSON files; it never writes to NASQO or posts accounting
entries. A reviewed mapping is required before the application importer can
create drafts.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any


REQUIRED_TABLES = {
    "products",
    "customers",
    "stores",
    "currencies",
    "input_move",
    "bill_details",
    "bin",
    "store_trans",
}


def rows(connection: sqlite3.Connection, table: str) -> list[dict[str, Any]]:
    cursor = connection.execute(f'SELECT * FROM "{table}"')
    columns = [column[0] for column in cursor.description]
    return [dict(zip(columns, row)) for row in cursor.fetchall()]


def decimal(value: Any) -> str:
    try:
        return str(Decimal(str(value or "0")))
    except (InvalidOperation, ValueError):
        return "0"


def summarize_bill_types(moves: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: Counter[tuple[Any, Any]] = Counter(
        (move.get("bill_type"), move.get("bstate")) for move in moves
    )
    return [
        {"billType": bill_type, "state": state, "count": count}
        for (bill_type, state), count in sorted(grouped.items(), key=str)
    ]


def build_report(source: Path, connection: sqlite3.Connection) -> dict[str, Any]:
    table_names = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        )
    }
    missing = sorted(REQUIRED_TABLES - table_names)
    if missing:
        raise ValueError(f"Unsupported backup; missing tables: {', '.join(missing)}")

    products = rows(connection, "products")
    customers = rows(connection, "customers")
    stores = rows(connection, "stores")
    currencies = rows(connection, "currencies")
    moves = rows(connection, "input_move")
    details = rows(connection, "bill_details")
    stock_moves = rows(connection, "bin")
    transfers = rows(connection, "store_trans")

    return {
        "format": "nasqo-sqlite-review-v1",
        "source": source.name,
        "readOnly": True,
        "importPolicy": {
            "target": "current-company",
            "financialDocuments": "draft-only",
            "postAccounting": False,
            "requiresBillTypeMapping": True,
            "requiresChartOfAccountsMapping": True,
        },
        "counts": {
            "products": len(products),
            "customers": len(customers),
            "stores": len(stores),
            "currencies": len(currencies),
            "financialMoves": len(moves),
            "billDetails": len(details),
            "stockMoves": len(stock_moves),
            "storeTransfers": len(transfers),
        },
        "currencies": currencies,
        "stores": stores,
        "billTypes": summarize_bill_types(moves),
        "warnings": [
            "The source has no chart of accounts or tax-rate mapping.",
            "Financial documents remain drafts until a bill-type mapping is approved.",
            "Existing NASQO products and customers require duplicate matching review.",
        ],
        "records": {
            "products": [
                {
                    "sourceId": product.get("id"),
                    "name": product.get("name"),
                    "barcode": product.get("barcode"),
                    "unit": product.get("unit"),
                    "salePrice": decimal(product.get("price")),
                    "purchasePrice": decimal(product.get("price2")),
                }
                for product in products
            ],
            "customers": [
                {
                    "sourceId": customer.get("id"),
                    "name": customer.get("name"),
                    "phone": customer.get("phon") or customer.get("phone"),
                    "creditLimit": decimal(customer.get("lim")),
                }
                for customer in customers
            ],
            "financialMoves": [
                {
                    "sourceId": move.get("id"),
                    "billType": move.get("bill_type"),
                    "state": move.get("bstate"),
                    "customerId": move.get("per_id"),
                    "debit": decimal(move.get("dain")),
                    "credit": decimal(move.get("madin")),
                    "date": {
                        "year": move.get("year"),
                        "month": move.get("month"),
                        "day": move.get("day"),
                    },
                }
                for move in moves
            ],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backup", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source = args.backup.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    try:
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("SQLite integrity check failed")
        report = build_report(source, connection)
    finally:
        connection.close()

    (output / "import_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({"output": str(output), **report["counts"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()