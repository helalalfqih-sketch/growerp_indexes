#!/usr/bin/env python3
"""Read-only staging bundle for a legacy NASQO SQLite POS backup.

No REST calls, target database connection, accounting posting, or import action
exists in this program. Output is local and must be kept private.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any

MAX_SOURCE_SIZE = 256 * 1024 * 1024

REQUIRED_COLUMNS: dict[str, set[str]] = {
    "products": {"id", "name", "barcode", "price", "price2", "unit"},
    "customers": {"id", "name", "type"},
    "stores": {"id", "name"},
    "currencies": {"id", "name"},
    "input_move": {"id", "bill_type", "bstate", "per_id", "dain", "madin"},
    "bill_details": {"id", "bill_id", "prod_id"},
    "bin": {"id"},
    "store_trans": {"id", "store_id1", "store_id2"},
}

# Any source tables outside this catalog are retained in the manifest (and the
# private raw snapshot when requested), never silently ignored.
COVERAGE = {
    "products": "product-candidate",
    "customers": "party-candidate-role-unverified",
    "stores": "location-candidate",
    "currencies": "currency-mapping-required",
    "input_move": "financial-type-and-accounts-unverified",
    "bill_details": "financial-line-unverified",
    "bill_details2": "supplementary-financial-line-unverified",
    "bin": "archived-financial-source-unverified",
    "bin_bill_details": "archived-financial-line-unverified",
    "bin_bill_details2": "archived-supplementary-line-unverified",
    "store_trans": "stock-transfer-unverified",
    "trans_details": "stock-transfer-line-unverified",
    "boxes": "cashbox-mapping-required",
    "cboxes": "cashbox-mapping-required",
    "offers": "offer-mapping-required",
    "offer_bill_details": "offer-line-mapping-required",
    "offer_sbill_details": "offer-line-mapping-required",
    "macc": "chart-of-accounts-source-review",
    "meters": "sector-extension-review",
    "meters_move": "sector-extension-review",
    "shtable": "auxiliary-source-review",
    "alerts": "configuration-source-review",
}


class MigrationBlocked(ValueError):
    """An unsafe or unsupported attempt to stage a backup."""


def identifier(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def rows(db: sqlite3.Connection, table: str):
    cur = db.execute("SELECT * FROM " + identifier(table))
    columns = [column[0] for column in cur.description]
    for values in cur:
        yield dict(zip(columns, values))


def fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def open_source(path: Path) -> sqlite3.Connection:
    if not path.is_file() or path.stat().st_size < 100:
        raise MigrationBlocked("Source is missing or too small")
    if path.stat().st_size > MAX_SOURCE_SIZE:
        raise MigrationBlocked("Source exceeds the supported 256 MiB review limit")
    with path.open("rb") as stream:
        if stream.read(16) != b"SQLite format 3\x00":
            raise MigrationBlocked("Source is not a SQLite 3 database")
    db = sqlite3.connect(path.resolve().as_uri() + "?mode=ro&immutable=1", uri=True)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA query_only = ON")
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        db.close()
        raise MigrationBlocked("Source SQLite integrity check did not pass")
    return db


def duplicate_groups(db: sqlite3.Connection, table: str, column: str) -> int:
    query = (
        "SELECT COUNT(*) FROM (SELECT LOWER(TRIM(CAST(" + identifier(column)
        + " AS TEXT))) AS value FROM " + identifier(table)
        + " WHERE " + identifier(column) + " IS NOT NULL AND TRIM(CAST("
        + identifier(column) + " AS TEXT)) <> '' "
        + "GROUP BY value HAVING COUNT(*) > 1)"
    )
    return int(db.execute(query).fetchone()[0])


def missing_ref(db: sqlite3.Connection, child: str, field: str,
                parent: str, key: str = "id") -> int:
    query = (
        "SELECT COUNT(*) FROM " + identifier(child) + " c "
        + "LEFT JOIN " + identifier(parent) + " p ON c."
        + identifier(field) + " = p." + identifier(key)
        + " WHERE c." + identifier(field) + " IS NOT NULL"
        + " AND c." + identifier(field) + " <> 0"
        + " AND p." + identifier(key) + " IS NULL"
    )
    return int(db.execute(query).fetchone()[0])


def analyze(db: sqlite3.Connection, source: Path) -> tuple[dict, dict]:
    names = [
        name for (name,) in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
    ]
    columns = {
        name: {row[1] for row in db.execute(
            "PRAGMA table_info(" + identifier(name) + ")"
        )}
        for name in names
    }
    missing_tables = sorted(REQUIRED_COLUMNS.keys() - columns.keys())
    if missing_tables:
        raise MigrationBlocked("Missing tables: " + ", ".join(missing_tables))
    missing_columns = {
        table: sorted(required - columns[table])
        for table, required in REQUIRED_COLUMNS.items()
        if required - columns[table]
    }
    if missing_columns:
        raise MigrationBlocked("Missing columns: " + json.dumps(missing_columns))

    counts = {
        name: int(db.execute(
            "SELECT COUNT(*) FROM " + identifier(name)
        ).fetchone()[0])
        for name in names
    }
    fk_issues = len(db.execute("PRAGMA foreign_key_check").fetchall())
    refs = {
        "input_move.per_id -> customers.id":
            missing_ref(db, "input_move", "per_id", "customers"),
        "bill_details.bill_id -> input_move.id":
            missing_ref(db, "bill_details", "bill_id", "input_move"),
        "bill_details.prod_id -> products.id":
            missing_ref(db, "bill_details", "prod_id", "products"),
        "store_trans.store_id1 -> stores.id":
            missing_ref(db, "store_trans", "store_id1", "stores"),
        "store_trans.store_id2 -> stores.id":
            missing_ref(db, "store_trans", "store_id2", "stores"),
    }
    for table, field, parent in (
        ("bill_details", "store_id", "stores"),
        ("bill_details2", "bill_id", "input_move"),
        ("trans_details", "bill_id", "store_trans"),
        ("trans_details", "prod_id", "products"),
        ("input_move", "cur", "currencies"),
        ("products", "cur", "currencies"),
    ):
        if table in counts and field in columns[table]:
            refs[f"{table}.{field} -> {parent}.id"] = missing_ref(
                db, table, field, parent
            )

    bill_types = [
        {"sourceType": item[0], "sourceState": item[1], "count": int(item[2])}
        for item in db.execute(
            "SELECT bill_type, bstate, COUNT(*) FROM input_move "
            "GROUP BY bill_type, bstate ORDER BY bill_type, bstate"
        )
    ]
    store_ids = [item[0] for item in db.execute("SELECT id FROM stores ORDER BY id")]
    currency_ids = [
        item[0] for item in db.execute("SELECT id FROM currencies ORDER BY id")
    ]
    party_types = [
        item[0] for item in db.execute(
            "SELECT DISTINCT type FROM customers ORDER BY type"
        )
    ]
    raw_equal = sum(
        1 for item in db.execute("SELECT dain, madin FROM input_move")
        if str(item[0] or "").strip() == str(item[1] or "").strip()
    )
    movements_without_main_lines = int(db.execute(
        "SELECT COUNT(*) FROM input_move a "
        "WHERE NOT EXISTS (SELECT 1 FROM bill_details b WHERE b.bill_id = a.id)"
    ).fetchone()[0])
    duplicate_names = duplicate_groups(db, "products", "name")
    duplicate_barcodes = duplicate_groups(db, "products", "barcode")

    manifest = {
        "format": "nasqo-legacy-staging-v1",
        "source": {
            "filename": source.name,
            "sha256": fingerprint(source),
            "sizeBytes": source.stat().st_size,
            "sqliteUserVersion": int(db.execute("PRAGMA user_version").fetchone()[0]),
        },
        "reviewOnly": True,
        "targetDatabaseModified": False,
        "financialPostingAllowed": False,
        "targetCompany": None,
        "approvalStatus": "BLOCKED_PENDING_MANUAL_MAPPING",
        "tableCount": len(counts),
        "totalRows": sum(counts.values()),
        "tables": {
            name: {
                "rows": count,
                "scope": COVERAGE.get(name, "UNKNOWN_REQUIRES_MAPPING"),
                "transferredToNASQO": False,
            }
            for name, count in counts.items()
        },
        "checks": {
            "sqliteIntegrity": "ok",
            "declaredForeignKeyViolations": fk_issues,
            "missingReferencedRows": refs,
            "duplicateProductNameGroups": duplicate_names,
            "duplicateNonemptyBarcodeGroups": duplicate_barcodes,
            "rawDainEqualsRawMadinRows": raw_equal,
            "movementsWithoutMainBillLines": movements_without_main_lines,
            "chartOfAccountsRows": counts.get("macc", 0),
        },
        "billTypeCounts": bill_types,
        "blockedReasons": [
            "Target company is not confirmed",
            "Source bill_type semantics have not been approved",
            "Source financial fields dain/madin are NOT debit/credit mappings",
            "Currency, tax, accounts, units and parties need manual mapping",
            "Referenced warehouse/currency gaps require resolution",
            "Duplicate matching requires explicit confirmation",
            "Archive, offers, cashboxes and sector extension tables need review",
        ],
    }
    template = {
        "format": "nasqo-manual-mapping-template-v1",
        "approved": False,
        "targetCompanyPartyId": None,
        "sourceFileSha256": manifest["source"]["sha256"],
        "sourceCurrencyToTargetCurrencyUomId": {
            str(key): None for key in currency_ids
        },
        "sourceStoreToTargetLocationId": {
            str(key): None for key in store_ids
        },
        "sourcePartyTypeToTargetRole": {
            str(key): None for key in party_types
        },
        "sourceBillTypeToNasqoDocumentType": {
            str(entry["sourceType"]): None for entry in bill_types
        },
        "taxTreatment": None,
        "unitConversions": {},
        "productMatchesBySourceId": {},
        "partyMatchesBySourceId": {},
        "accounts": {
            key: None for key in (
                "cash", "bank", "receivables", "payables", "salesRevenue",
                "purchases", "inventory", "costOfGoodsSold", "salesDiscount",
                "purchaseDiscount", "taxPayable", "taxRecoverable"
            )
        },
        "rules": {
            "automaticProductOrPartyMatch": False,
            "financialMode": "review-only-unposted-candidate",
            "postAccounting": False,
            "allowAutomaticImport": False,
            "sourceReferenceMustBeUnique": True,
        },
    }
    return manifest, template


def json_safe(value: Any):
    if isinstance(value, bytes):
        return {"__sqlite_blob_base64__": base64.b64encode(value).decode("ascii")}
    if isinstance(value, (dt.datetime, dt.date)):
        return value.isoformat()
    return value


def private_file(path: Path):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    return os.fdopen(descriptor, "w", encoding="utf-8", newline="\n")


def dump_json(path: Path, content: dict) -> None:
    with private_file(path) as out:
        json.dump(content, out, ensure_ascii=False, indent=2, default=json_safe)
        out.write("\n")


def dump_jsonl(path: Path, records) -> int:
    total = 0
    with private_file(path) as out:
        for item in records:
            out.write(json.dumps(item, ensure_ascii=False, default=json_safe))
            out.write("\n")
            total += 1
    return total


def create_private_bundle(source: Path, destination: Path,
                          stage_records: bool = False) -> dict:
    source = source.expanduser().resolve()
    requested = destination.expanduser().absolute()
    if requested.exists() or requested.is_symlink():
        raise MigrationBlocked("Refusing to overwrite an existing staging directory")
    destination = requested.resolve(strict=False)
    repo_root = Path(__file__).resolve().parents[1]
    if destination == repo_root or repo_root in destination.parents:
        raise MigrationBlocked("Private migration output must stay OUTSIDE Git")
    with closing(open_source(source)) as db:
        manifest, mapping = analyze(db, source)
        # Keep a link to the original backup rather than changing or deleting it.
        destination.mkdir(mode=0o700, parents=True, exist_ok=False)
        dump_json(destination / "mapping_template.json", mapping)
        if stage_records:
            raw = destination / "source_tables"
            raw.mkdir(mode=0o700)
            for index, table in enumerate(sorted(manifest["tables"])):
                safe_name = re.sub(r"[^a-zA-Z0-9_.-]", "_", table)
                relative = f"source_tables/{index:02d}_{safe_name}.jsonl"
                amount = dump_jsonl(destination / relative, rows(db, table))
                if amount != manifest["tables"][table]["rows"]:
                    raise MigrationBlocked(f"Snapshot count changed for table {table}")
                manifest["tables"][table]["localSnapshot"] = relative

            prefix = manifest["source"]["sha256"][:16]
            def ref(table: str, key: Any) -> str:
                return f"sqlite:{prefix}:{table}:{key}"

            dump_jsonl(destination / "product_candidates.jsonl", (
                {
                    "sourceRef": ref("products", product["id"]),
                    "sourceId": product["id"],
                    "name": product["name"],
                    "barcode": product["barcode"],
                    "unit": product["unit"],
                    "salePriceRaw": product["price"],
                    "purchasePriceRaw": product["price2"],
                    "targetProductId": None,
                    "status": "UNREVIEWED"
                }
                for product in rows(db, "products")
            ))
            dump_jsonl(destination / "party_candidates.jsonl", (
                {
                    "sourceRef": ref("customers", party["id"]),
                    "sourceId": party["id"],
                    "sourceRoleCode": party["type"],
                    "name": party["name"],
                    "phoneRaw": party.get("phon") or party.get("phone"),
                    "targetPartyId": None,
                    "status": "UNREVIEWED"
                }
                for party in rows(db, "customers")
            ))
            dump_jsonl(destination / "location_candidates.jsonl", (
                {
                    "sourceRef": ref("stores", store["id"]),
                    "sourceId": store["id"],
                    "name": store["name"],
                    "targetLocationId": None,
                    "status": "UNREVIEWED"
                }
                for store in rows(db, "stores")
            ))
            dump_jsonl(destination / "financial_candidates.jsonl", (
                {
                    "sourceRef": ref("input_move", move["id"]),
                    "sourceId": move["id"],
                    "billTypeCode": move["bill_type"],
                    "sourceStateCode": move["bstate"],
                    "sourcePartyId": move["per_id"],
                    "sourceCurrencyId": move.get("cur"),
                    "rawDain": move["dain"],
                    "rawMadin": move["madin"],
                    "dateParts": [move.get("year"), move.get("month"), move.get("day")],
                    "targetDocumentType": None,
                    "targetFinancialDocumentId": None,
                    "status": "BLOCKED_PENDING_MAPPING",
                    "posted": False
                }
                for move in rows(db, "input_move")
            ))
            # Every raw table is retained locally. No target schema writes occur.
        dump_json(destination / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original legacy SQLite .back")
    parser.add_argument("--output", type=Path, required=True,
                        help="New private staging directory outside repository")
    parser.add_argument("--stage-records", action="store_true",
                        help="Opt in to local PII-containing JSONL; never commit files")
    args = parser.parse_args()
    result = create_private_bundle(args.source, args.output, args.stage_records)
    print(json.dumps({
        "status": result["approvalStatus"],
        "sqliteIntegrity": result["checks"]["sqliteIntegrity"],
        "tableCount": result["tableCount"],
        "totalSourceRows": result["totalRows"],
        "stagedRawRecordsLocally": args.stage_records,
        "postedFinancialDocuments": 0,
        "writtenToNASQO": 0,
    }))


if __name__ == "__main__":
    main()
