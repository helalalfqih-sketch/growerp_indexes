#!/usr/bin/env python3
"""Check NASQO company identity before a staging-only migration rehearsal.

Never modifies the NASQO backend or the source SQLite database. An optional
authenticated, HTTPS GET is restricted to staging-like hostnames; a JSON file
is untrusted offline evidence, not backend verification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import ssl
import unicodedata
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, HTTPSHandler


class IdentityBlocked(ValueError):
    pass


def normalized_name(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    # Arabic alef, yaa and tatweel variations are display differences.
    text = re.sub("[أإآٱ]", "ا", text).replace("ى", "ي").replace("ـ", "")
    return "".join(c for c in text if c.isalnum())


def verify_response(mapping: dict, manifest: dict, response: dict, *,
                    authenticated_staging: bool = False) -> dict:
    """Compare partyId (never pseudoId) with a single company record."""
    if mapping.get("sourceFileSha256") != manifest.get("source", {}).get("sha256"):
        raise IdentityBlocked("Source fingerprint mismatch in mapping/manifest")
    candidate = str(mapping.get("targetCompanyPartyIdCandidate") or "").strip()
    if not candidate or candidate != str(mapping.get("targetCompanyPartyIdCandidate")):
        raise IdentityBlocked("Company partyId candidate is missing or malformed")
    expected = [
        mapping.get("targetCompanyName", ""),
        *(mapping.get("targetCompanyAliases") or []),
    ]
    expected_names = {normalized_name(x) for x in expected if x}
    if not expected_names or mapping.get("approved") is True:
        raise IdentityBlocked("Expected company or locked review state is invalid")

    records = response.get("companies")
    if not isinstance(records, list) or len(records) > 2:
        raise IdentityBlocked("Unexpected Company API response shape")
    matches = [
        item for item in records if isinstance(item, dict)
        and str(item.get("partyId", "")).strip() == candidate
    ]
    pseudo_only = any(
        isinstance(item, dict)
        and str(item.get("pseudoId", "")).strip() == candidate
        and str(item.get("partyId", "")).strip() != candidate
        for item in records
    )
    unique = len(matches) == 1
    name_matches = bool(unique and
                        normalized_name(matches[0].get("name", "")) in expected_names)
    reasons = []
    if not unique:
        reasons.append("Exact company partyId not returned exactly once")
    if pseudo_only:
        reasons.append("Candidate matched pseudoId only: NOT the company partyId")
    if unique and not name_matches:
        reasons.append("Company name does not match approved name candidates")
    if not authenticated_staging:
        reasons.append("Offline response file does NOT verify live backend identity")
    reasons.extend([
        "Financial and tax mappings have not been approved",
        "No target Moqui/PostgreSQL staging tenant has been certified",
    ])
    status = (
        "MATCHED_AUTHENTICATED_STAGING_ONLY"
        if unique and name_matches and authenticated_staging
        else "MATCHED_UNTRUSTED_FILE_ONLY"
        if unique and name_matches
        else "BLOCKED_IDENTITY_MISMATCH"
    )
    return {
        "format": "nasqo-staging-company-identity-v1",
        "candidatePartyId": candidate,
        "companyNameMatched": name_matches,
        "companyPartyIdMatched": unique,
        "pseudoIdOnly": pseudo_only,
        "identityStatus": status,
        "authenticatedStagingResponse": authenticated_staging,
        "safeToCreateFinancialDocuments": False,
        "safeToPostAccounting": False,
        "stagingMigrationApproved": False,
        "targetRowsWritten": 0,
        "blockers": reasons,
    }


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def validate_staging_host(raw_url: str) -> str:
    """Allow only an explicitly stage/test labeled HTTPS host (no production)."""
    parsed = urlsplit(raw_url)
    hostname = (parsed.hostname or "").lower()
    if (parsed.scheme != "https" or not hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")
            or not re.search(r"(^|[.-])(staging|stage|test)([.-]|$)", hostname)):
        raise IdentityBlocked("Only a staging/test HTTPS origin is allowed")
    if hostname in {"erp.indexesstore.com", "backend.growerp.com",
                    "backend.growerp.org"}:
        raise IdentityBlocked("Refusing known production/upstream domains")
    return urlunsplit(("https", parsed.netloc, "", "", ""))


def fetch_staging_company(base_url: str, candidate: str, api_key: str) -> dict:
    """Perform one authenticated GET, never post, with redirects disabled."""
    origin = validate_staging_host(base_url)
    if not api_key or len(api_key.strip()) < 10:
        raise IdentityBlocked("Authenticated staging API key is unavailable")
    path = "/rest/s1/growerp/100/Company"
    query = urlencode({"companyPartyId": candidate, "limit": "2"})
    req = Request(origin + path + "?" + query, method="GET",
                  headers={"api_key": api_key, "Accept": "application/json"})
    opener = build_opener(HTTPSHandler(context=ssl.create_default_context()),
                          _NoRedirect())
    try:
        with opener.open(req, timeout=12) as resp:
            if resp.status != 200:
                raise IdentityBlocked("Staging Company GET did not succeed")
            body = resp.read(1024 * 1024 + 1)
            if len(body) > 1024 * 1024:
                raise IdentityBlocked("Unexpected oversized company response")
            parsed = json.loads(body.decode("utf-8"))
            if not isinstance(parsed, dict):
                raise IdentityBlocked("Unexpected Company API JSON type")
            return parsed
    except (HTTPError, URLError, TimeoutError, ssl.SSLError) as exc:
        # Do not leak headers, query details, cookies, or response bodies.
        raise IdentityBlocked(
            "Authenticated staging Company GET failed (" + type(exc).__name__ + ")"
        ) from None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(mapping_path: Path, manifest_path: Path, *,
        source: Path | None = None, response_file: Path | None = None,
        base_url: str | None = None, api_key_env: str = "NASQO_STAGING_API_KEY") -> dict:
    if response_file is not None and base_url is not None:
        raise IdentityBlocked("Choose either offline evidence or staging GET")
    mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if source is not None and sha256_file(source) != manifest["source"]["sha256"]:
        raise IdentityBlocked("Original backup fingerprint mismatch")
    if mapping.get("targetCompanyVerified") is True:
        raise IdentityBlocked("This tool cannot endorse a previously claimed verification")
    if mapping.get("approved") is True:
        raise IdentityBlocked("Reviewed mapping must remain unapproved here")
    if base_url is None and response_file is None:
        return {
            "format": "nasqo-staging-company-identity-v1",
            "candidatePartyId": str(mapping.get("targetCompanyPartyIdCandidate") or ""),
            "identityStatus": "PENDING_AUTHENTICATED_STAGING_LOOKUP",
            "authenticatedStagingResponse": False,
            "safeToCreateFinancialDocuments": False,
            "safeToPostAccounting": False,
            "stagingMigrationApproved": False,
            "targetRowsWritten": 0,
            "blockers": ["No authenticated staging Company API response available"],
        }
    if response_file is not None:
        response = json.loads(response_file.read_text(encoding="utf-8"))
        return verify_response(mapping, manifest, response, authenticated_staging=False)
    key = os.environ.get(api_key_env, "")
    response = fetch_staging_company(
        base_url or "", str(mapping.get("targetCompanyPartyIdCandidate") or ""), key
    )
    return verify_response(mapping, manifest, response, authenticated_staging=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--response-file", type=Path,
                        help="Untrusted OFFLINE JSON fixture (never live proof)")
    parser.add_argument("--staging-base-url",
                        help="Stage/test HTTPS base URL ONLY; never production")
    parser.add_argument("--api-key-env", default="NASQO_STAGING_API_KEY")
    args = parser.parse_args()
    result = run(args.mapping, args.manifest, source=args.source,
                 response_file=args.response_file,
                 base_url=args.staging_base_url, api_key_env=args.api_key_env)
    # This sanitized result contains no customer fields or secrets.
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
