#!/usr/bin/env python3
"""Prepare review-only NASQO model candidates from a legacy SQLite backup.

This program has NO backend client, NO tenant writes, and NO finance posting.
It emits only aggregate metadata unless --include-private-records is provided.
The original SQLite source is opened immutable and read-only. Generated
candidate records are NOT API payloads or approved target objects.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from collections import Counter
from contextlib import closing
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable

from nasqo_legacy_staging import (
    MigrationBlocked,
    analyze,
    dump_json,
    dump_jsonl,
    open_source,
    rows,
)

REVIEW_FORMAT = "nasqo-model-candidate-review-v1"
SOURCE_MODELS = {
    "products": "Product",
    "customers": "Company (party role pending)",
    "stores": "Location",
    "input_move": "FinDoc (classification pending)",
    "bill_details": "FinDocItem (classification pending)",
    "bill_details2": "FinDocItem (supplementary lines pending)",
}
MAPPING_REQUIRED = [
    "Staging tenant and company identity",
    "Product type, unit and currency mapping",
    "Party role codes, deduplication and permissions",
    "Inventory warehouse and transfer references",
    "Every bill_type and source document state",
    "Financial signs, taxes, exchange rates and approved GL accounts",
]


def canonical_text(value: Any) -> str:
    if value is None:
        return ""
    text = unicodedata.normalize("NFKC", str(value)).casefold()
    text = re.sub("[أإآٱ]", "ا", text).replace("ى", "ي").replace("ـ", "")
    return " ".join(text.split())


def string_or_none(value: Any) -> str | None:
    if value is None:
        return None
    value = str(value).strip()
    return value if value else None


def decimal_issue(value: Any) -> str | None:
    if value is None or str(value).strip() == "":
        return "MISSING_NUMERIC_VALUE"
    try:
        parsed = Decimal(str(value).strip())
    except (InvalidOperation, ValueError):
        return "INVALID_DECIMAL"
    return None if parsed.is_finite() else "NONFINITE_DECIMAL"


def verified_source_reference(source_sha: str, table: str, key: Any) -> str:
    if key is None or str(key).strip() == "":
        raise MigrationBlocked(f"Missing stable source identifier in {table}")
    return f"sqlite:{source_sha}:{table}:{key}"


def check_mapping(mapping: dict, source_sha: str) -> None:
    if mapping.get("sourceFileSha256") != source_sha:
        raise MigrationBlocked("Mapping source SHA-256 does not match backup")
    if mapping.get("approved") is not False:
        raise MigrationBlocked("This preview requires an unapproved mapping")
    rules = mapping.get("rules") or {}
    if rules.get("allowAutomaticImport") is not False:
        raise MigrationBlocked("Automatic import MUST remain disabled")
    if rules.get("postAccounting") is not False:
        raise MigrationBlocked("Financial posting MUST remain disabled")
    # Never silently confuse a production company ID with a staging tenant.
    if mapping.get("targetCompanyPartyId") not in (None, ""):
        raise MigrationBlocked("Staging target ID may not be set by this review tool")
    if mapping.get("targetCompanyVerified") is True:
        raise MigrationBlocked("Verified staging identity requires a separate gate")


def prepare_review(source: Path, mapping_path: Path,
                   destination: Path, include_private_records: bool = False) -> dict:
    source = source.expanduser().resolve()
    mapping = json.loads(mapping_path.expanduser().read_text(encoding="utf-8"))
    with closing(open_source(source)) as db:
        legacy_manifest, _ = analyze(db, source)
        source_sha = legacy_manifest["source"]["sha256"]
        check_mapping(mapping, source_sha)

        counts = {
            "products": legacy_manifest["tables"]["products"]["rows"],
            "parties": legacy_manifest["tables"]["customers"]["rows"],
            "locations": legacy_manifest["tables"]["stores"]["rows"],
            "financialMovements": legacy_manifest["tables"]["input_move"]["rows"],
            "primaryInvoiceLines": legacy_manifest["tables"]["bill_details"]["rows"],
            "extraInvoiceLines": legacy_manifest["tables"].get("bill_details2", {}).get("rows", 0),
        }
        prod_names = Counter(
            canonical_text(row["name"]) for row in rows(db, "products")
            if canonical_text(row.get("name"))
        )
        prod_barcodes = Counter(
            canonical_text(row["barcode"]) for row in rows(db, "products")
            if canonical_text(row.get("barcode"))
        )
        party_names = Counter(
            canonical_text(row["name"]) for row in rows(db, "customers")
            if canonical_text(row.get("name"))
        )
        finance_types = [
            {"sourceCode": row[0], "sourceState": row[1], "count": row[2]}
            for row in db.execute(
                "SELECT bill_type, bstate, COUNT(*) FROM input_move "
                "GROUP BY bill_type,bstate ORDER BY bill_type,bstate"
            )
        ]
        source_role_codes = [
            {"code": row[0], "count": row[1]}
            for row in db.execute(
                "SELECT type, COUNT(*) FROM customers GROUP BY type ORDER BY type"
            )
        ]

        stats = {
            "duplicateProductNameGroups": sum(n > 1 for n in prod_names.values()),
            "duplicateNonemptyBarcodeGroups": sum(n > 1 for n in prod_barcodes.values()),
            "duplicatePartyNameGroups": sum(n > 1 for n in party_names.values()),
            "productRowsWithBlankName": 0,
            "partyRowsWithBlankName": 0,
            "productRowsWithUnverifiedPrices": 0,
            "sourceFinancialRowsAutomaticallyMapped": 0,
        }
        for p in rows(db, "products"):
            if not canonical_text(p["name"]):
                stats["productRowsWithBlankName"] += 1
            if decimal_issue(p.get("price")) or decimal_issue(p.get("price2")):
                stats["productRowsWithUnverifiedPrices"] += 1
        for p in rows(db, "customers"):
            if not canonical_text(p["name"]):
                stats["partyRowsWithBlankName"] += 1

        report = {
            "format": REVIEW_FORMAT,
            "sourceSha256": source_sha,
            "sourceRows": legacy_manifest["totalRows"],
            "sourceTableCount": legacy_manifest["tableCount"],
            "sourceSummary": counts,
            "sourceTypeCounts": {"financial": finance_types, "party": source_role_codes},
            "proposedModels": SOURCE_MODELS,
            "reviewFindings": stats,
            "sourceReferenceProblems": legacy_manifest["checks"]["missingReferencedRows"],
            "requiredApprovals": MAPPING_REQUIRED,
            "target": {
                "companyName": mapping.get("targetCompanyName"),
                "partyIdCandidate": mapping.get("targetCompanyPartyIdCandidate"),
                "companyPartyId": None,
                "stagingTenantVerified": False,
            },
            "reviewOnly": True,
            "allRecordsMappedToNASQO": False,
            "privateRowsStagedLocally": include_private_records,
            "targetDatabaseWrites": 0,
            "postedFinancialEntries": 0,
            "autoImportAllowed": False,
        }

        destination = destination.expanduser().absolute().resolve(strict=False)
        repo_root = Path(__file__).resolve().parents[1]
        if destination == repo_root or repo_root in destination.parents:
            raise MigrationBlocked("Review directory must be OUTSIDE the Git checkout")
        if destination.exists() or destination.is_symlink():
            raise MigrationBlocked("Refusing to overwrite an existing review folder")
        destination.mkdir(parents=True, mode=0o700, exist_ok=False)

        if include_private_records:
            def product_candidates():
                for p in rows(db, "products"):
                    reasons = ["VERIFY_TYPE_UNIT_CURRENCY_AND_PRICES"]
                    if not canonical_text(p.get("name")):
                        reasons.append("MISSING_PRODUCT_NAME")
                    if prod_names[canonical_text(p.get("name"))] > 1:
                        reasons.append("DUPLICATE_PRODUCT_NAME")
                    if p.get("barcode") and prod_barcodes[canonical_text(p["barcode"])] > 1:
                        reasons.append("DUPLICATE_BARCODE")
                    if decimal_issue(p.get("price")) or decimal_issue(p.get("price2")):
                        reasons.append("INVALID_OR_MISSING_PRICE")
                    yield {
                        "sourceRef": verified_source_reference(source_sha, "products", p["id"]),
                        "sourceId": p["id"],
                        "source": {
                            "name": p.get("name"),
                            "barcode": p.get("barcode"),
                            "unitRaw": p.get("unit"),
                            "priceRaw": p.get("price"),
                            "price2Raw": p.get("price2"),
                            "currencyCodeRaw": p.get("cur"),
                        },
                        # Valid field names from Flutter Product; no guessed amounts.
                        "proposedNASQO": {
                            "productId": None, "pseudoId": None,
                            "productName": string_or_none(p.get("name")),
                            "barcode": string_or_none(p.get("barcode")),
                            "productTypeId": None, "amountUom": None,
                            "currency": None, "price": None, "listPrice": None,
                        },
                        "status": "REVIEW_REQUIRED", "reasons": reasons,
                    }

            def party_candidates():
                for p in rows(db, "customers"):
                    reasons = ["CONFIRM_CUSTOMER_SUPPLIER_ROLE_AND_DUPLICATES"]
                    if not canonical_text(p.get("name")):
                        reasons.append("MISSING_PARTY_NAME")
                    if party_names[canonical_text(p.get("name"))] > 1:
                        reasons.append("DUPLICATE_PARTY_NAME")
                    yield {
                        "sourceRef": verified_source_reference(source_sha, "customers", p["id"]),
                        "sourceId": p["id"],
                        "sourceRoleCode": p.get("type"),
                        "source": {
                            "name": p.get("name"),
                            "phoneRaw": p.get("phon") or p.get("phone"),
                            "creditLimitRaw": p.get("lim"),
                        },
                        "proposedNASQO": {
                            "partyId": None, "pseudoId": None,
                            "name": string_or_none(p.get("name")),
                            "role": None, "telephoneNr": None,
                        },
                        "status": "REVIEW_REQUIRED", "reasons": reasons,
                    }

            def location_candidates():
                for item in rows(db, "stores"):
                    yield {
                        "sourceRef": verified_source_reference(source_sha, "stores", item["id"]),
                        "sourceId": item["id"],
                        "sourceStatusCode": item.get("state"),
                        "proposedNASQO": {
                            "locationId": None, "pseudoId": None,
                            "locationName": string_or_none(item.get("name")),
                        },
                        "status": "REVIEW_REQUIRED",
                        "reasons": ["VERIFY_LOCATION_BRANCH_AND_STOCK_MAPPING"],
                    }

            def financial_holds():
                line_counts = {
                    row[0]: row[1] for row in db.execute(
                        "SELECT bill_id, COUNT(*) FROM bill_details GROUP BY bill_id"
                    )
                }
                extra_counts = {}
                if "bill_details2" in legacy_manifest["tables"]:
                    extra_counts = {
                        row[0]: row[1] for row in db.execute(
                            "SELECT bill_id, COUNT(*) FROM bill_details2 GROUP BY bill_id"
                        )
                    }
                for item in rows(db, "input_move"):
                    yield {
                        "sourceRef": verified_source_reference(source_sha, "input_move", item["id"]),
                        "sourceId": item["id"],
                        "billTypeCode": item.get("bill_type"),
                        "stateCode": item.get("bstate"),
                        "sourcePartyId": item.get("per_id"),
                        "sourceCurrencyId": item.get("cur"),
                        "mainLineCount": line_counts.get(item["id"], 0),
                        "supplementaryLineCount": extra_counts.get(item["id"], 0),
                        "proposedNASQO": {
                            "docType": None, "docSubType": None,
                            "grandTotal": None, "isPosted": False,
                            "invoiceId": None, "paymentId": None,
                            "transactionId": None,
                        },
                        "status": "BLOCKED_FINANCIAL_MAPPING",
                        "reasons": ["VERIFY_BILL_TYPE_AND_FINANCIAL_ACCOUNTS"],
                    }

            written = {
                "products": dump_jsonl(destination / "product_candidates.jsonl", product_candidates()),
                "parties": dump_jsonl(destination / "party_candidates.jsonl", party_candidates()),
                "locations": dump_jsonl(destination / "location_candidates.jsonl", location_candidates()),
                "financial": dump_jsonl(destination / "financial_holds.jsonl", financial_holds()),
            }
            if any(written[name] != counts[k] for name, k in [
                ("products", "products"), ("parties", "parties"),
                ("locations", "locations"), ("financial", "financialMovements")
            ]):
                raise MigrationBlocked("Candidate output row counts did not match source")
            report["localCandidateCounts"] = written

        dump_json(destination / "review_manifest.json", report)
        return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-private-records", action="store_true")
    args = parser.parse_args()
    result = prepare_review(
        args.source, args.mapping, args.output, args.include_private_records
    )
    print(json.dumps({
        "status": "REVIEW_ONLY_NO_NASQO_WRITES",
        "sourceTableCount": result["sourceTableCount"],
        "products": result["sourceSummary"]["products"],
        "parties": result["sourceSummary"]["parties"],
        "locations": result["sourceSummary"]["locations"],
        "financeBlocked": result["sourceSummary"]["financialMovements"],
        "postedFinancialEntries": 0,
        "privateRecordsOutput": args.include_private_records,
    }))


if __name__ == "__main__":
    main()
