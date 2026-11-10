#!/usr/bin/env python3
"""Read-only reconciliation gate for a legacy NASQO SQLite POS backup.

This produces AGGREGATE evidence only, never raw customer/product records.
It cannot authorize posting, update a target, or infer bill/party code meanings.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from contextlib import closing
from pathlib import Path

from nasqo_legacy_staging import analyze, open_source


def grouped_count(db, table: str, column: str) -> dict[str, int]:
    return {
        str(code): int(count)
        for code, count in db.execute(
            f'SELECT "{column}", COUNT(*) FROM "{table}" '
            f'GROUP BY "{column}" ORDER BY "{column}"'
        )
    }


def linkage_by_type(db) -> list[dict]:
    query = """
        SELECT m.bill_type, COUNT(*) AS movements,
          SUM(CASE WHEN EXISTS (
            SELECT 1 FROM bill_details b WHERE b.bill_id=m.id
          ) THEN 1 ELSE 0 END) AS with_main_lines,
          SUM(CASE WHEN EXISTS (
            SELECT 1 FROM bill_details2 e WHERE e.bill_id=m.id
          ) THEN 1 ELSE 0 END) AS with_supplementary_lines,
          SUM(CASE WHEN NOT EXISTS (
            SELECT 1 FROM bill_details b WHERE b.bill_id=m.id
          ) AND NOT EXISTS (
            SELECT 1 FROM bill_details2 e WHERE e.bill_id=m.id
          ) THEN 1 ELSE 0 END) AS without_either_lines
        FROM input_move m GROUP BY m.bill_type ORDER BY m.bill_type
    """
    return [
        {
            "billTypeUnclassified": code,
            "count": count,
            "withMainLines": main,
            "withSupplementaryLines": extra,
            "withoutEitherLineType": neither,
        }
        for code, count, main, extra, neither in db.execute(query)
    ]


def analyze_reconciliation(source: Path) -> dict:
    with closing(open_source(source)) as db:
        overview, _ = analyze(db, source)
        tables = overview["tables"]
        if "bill_details2" not in tables:
            # The basic 8-table fixture is still supported; do not falsify
            # missing supplementary tables or their totals.
            query = """
                SELECT bill_type, COUNT(*),
                SUM(CASE WHEN EXISTS (
                  SELECT 1 FROM bill_details d WHERE d.bill_id=m.id
                ) THEN 1 ELSE 0 END)
                FROM input_move m GROUP BY bill_type ORDER BY bill_type
            """
            types = [
                {
                    "billTypeUnclassified": code,
                    "count": count,
                    "withMainLines": main,
                    "withSupplementaryLines": 0,
                    "withoutEitherLineType": count - main,
                }
                for code, count, main in db.execute(query)
            ]
        else:
            types = linkage_by_type(db)
        n_product_multiunits = 0
        if "unit2" in {x[1] for x in db.execute("PRAGMA table_info(products)")}:
            n_product_multiunits = int(db.execute(
                "SELECT COUNT(*) FROM products "
                "WHERE unit2 IS NOT NULL AND TRIM(unit2) <> ''"
            ).fetchone()[0])
        moves = tables["input_move"]["rows"]
        line_types = {
            "primaryBillDetails": tables["bill_details"]["rows"],
            "supplementaryBillDetails": tables.get("bill_details2", {}).get("rows", 0),
            "stockTransferDetails": tables.get("trans_details", {}).get("rows", 0),
        }
        type_total = sum(item["count"] for item in types)
        primary_total = sum(item["withMainLines"] for item in types)
        supplementary_total = sum(item["withSupplementaryLines"] for item in types)
        if type_total != moves:
            raise ValueError("Source bill-type count reconciliation failed")

        missing_refs = overview["checks"]["missingReferencedRows"]
        return {
            "format": "nasqo-legacy-reconciliation-v1",
            "sourceHash": overview["source"]["sha256"],
            "readOnly": True,
            "totalTables": overview["tableCount"],
            "totalSourceRows": overview["totalRows"],
            "sourceCountByTable": {
                t: entry["rows"] for t, entry in tables.items()
            },
            "sourceFinance": {
                "movements": moves,
                "billTypeRowCountReconciled": type_total == moves,
                "perTypeLineCoverage": types,
                "movementsWithMainDetails": primary_total,
                "movementsWithSupplementaryDetails": supplementary_total,
                "lineRecordCounts": line_types,
                "sourceDainEqualsMadinRows": overview["checks"]["rawDainEqualsRawMadinRows"],
                "allBillTypesUnclassified": True,
                "postedEntries": 0,
            },
            "sourcePartyTypes": grouped_count(db, "customers", "type"),
            "sourceMultiUnitProductCount": n_product_multiunits,
            "duplicateProductNameGroups": overview["checks"]["duplicateProductNameGroups"],
            "unmatchedReferences": missing_refs,
            "foreignKeyDeclaredViolationCount": overview["checks"]["declaredForeignKeyViolations"],
            "interpretationWarnings": [
                "No bill_type, party type, GL account, tax or currency mapping is approved",
                "Neither dain nor madin is assumed to be a journal debit or credit",
                "Financial movements without bill_details may be other document types",
                "Unit multipliers and price columns need source-system semantics",
                "Unmatched reference counts are risks, not permission to drop rows",
            ],
            "dataCanBeStagedForReview": True,
            "dataCanBeImportedToProduction": False,
            "financialPostingAllowed": False,
            "destinationWrites": 0,
            "accountingReconciliationApproved": False,
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    target = args.output.expanduser().resolve()
    root = Path(__file__).resolve().parents[1]
    if root == target or root in target.parents:
        raise ValueError("Review output cannot be placed within Git checkout")
    if target.exists():
        raise ValueError("Refusing to overwrite existing reconciliation report")
    result = analyze_reconciliation(args.source)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps({
        "status": "REVIEW_ONLY_UNPOSTED",
        "sourceTables": result["totalTables"],
        "sourceRows": result["totalSourceRows"],
        "financialMovements": result["sourceFinance"]["movements"],
        "accountingPostingAllowed": False,
        "destinationWrites": 0,
    }))


if __name__ == "__main__":
    main()
