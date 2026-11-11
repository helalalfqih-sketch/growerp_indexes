#!/usr/bin/env python3
"""Read-only Shopify <-> GrowERP preview. No code path can update either store."""
from __future__ import annotations
import argparse
from decimal import Decimal, InvalidOperation
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

STORE = "ubhd8d-iz.myshopify.com"
CURRENCY = "YER"
QUERY = """
query Preview($first: Int!, $after: String) {
  shop { myshopifyDomain currencyCode }
  products(first: $first, after: $after, sortKey: ID) {
    pageInfo { hasNextPage endCursor }
    nodes {
      id title
      variants(first: 2) {
        pageInfo { hasNextPage }
        nodes { id sku price inventoryQuantity }
      }
    }
  }
}
"""

def request_json(url, headers, query=None):
    """GET or POST with fixed *query* GraphQL document, never a mutation."""
    body = json.dumps(query).encode() if query else None
    request = Request(url, data=body, headers=headers, method="POST" if body else "GET")
    with urlopen(request, timeout=30) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("Response too large; aborting incomplete snapshot")
    result = json.loads(data)
    if not isinstance(result, dict):
        raise ValueError("Invalid JSON response")
    return result

def assert_shop(shop):
    if not isinstance(shop, dict) or shop.get("myshopifyDomain") != STORE:
        raise ValueError("Shopify identity mismatch")
    if shop.get("currencyCode") != CURRENCY:
        raise ValueError("Currency mismatch: explicit approval required")

def read_shopify():
    domain = os.environ.get("SHOPIFY_SHOP_DOMAIN", STORE).strip().lower()
    version = os.environ.get("SHOPIFY_API_VERSION", "")
    token = os.environ.get("SHOPIFY_ADMIN_TOKEN", "")
    if domain != STORE:
        raise ValueError("This bridge is scoped to indexesstore.com only")
    if not token or not re.fullmatch(r"20[2-9][0-9]-(01|04|07|10)", version):
        raise ValueError("SHOPIFY_ADMIN_TOKEN and SHOPIFY_API_VERSION are required")
    endpoint = f"https://{STORE}/admin/api/{version}/graphql.json"
    headers = {"X-Shopify-Access-Token": token, "Content-Type": "application/json"}
    products, after = [], None
    for _ in range(100):
        result = request_json(endpoint, headers, {"query": QUERY, "variables": {"first": 50, "after": after}})
        if result.get("errors"):
            raise ValueError("Shopify GraphQL rejected the read-only query")
        data = result.get("data", {})
        assert_shop(data.get("shop"))
        conn = data.get("products", {})
        rows, page = conn.get("nodes"), conn.get("pageInfo")
        if not isinstance(rows, list) or not isinstance(page, dict):
            raise ValueError("Missing Shopify pagination fields")
        products.extend(rows)
        if not page.get("hasNextPage"):
            return {"shop": data["shop"], "products": products}
        cursor = page.get("endCursor")
        if not cursor or cursor == after:
            raise ValueError("Shopify cursor did not advance")
        after = cursor
    raise ValueError("Shopify pagination cap exceeded; refusing partial catalog")

def read_growerp():
    base = os.environ.get("GROWERP_BASE_URL", "").rstrip("/")
    key = os.environ.get("GROWERP_API_KEY", "")
    parsed = urlparse(base)
    if not key or parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("HTTPS test GROWERP_BASE_URL and read-only GROWERP_API_KEY required")
    products = []
    for page in range(100):
        params = urlencode({"start": page * 100, "limit": 100, "applicationId": "AppAdmin"})
        data = request_json(f"{base}/rest/s1/growerp/100/Products?{params}", {"api_key": key, "Accept": "application/json"})
        rows = data.get("products")
        if not isinstance(rows, list):
            raise ValueError("GrowERP Products endpoint returned unexpected data")
        products.extend(rows)
        if len(rows) < 100:
            return {"products": products}
    raise ValueError("GrowERP pagination cap exceeded; refusing partial catalog")

def money(value):
    if value is None or isinstance(value, bool):
        raise ValueError("Missing price")
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError) as e:
        raise ValueError("Invalid price") from e
    if not d.is_finite() or d < 0:
        raise ValueError("Invalid nonfinite or negative price")
    return format(d.normalize(), "f")

def index_by(items, key):
    if not isinstance(items, list):
        raise ValueError("Product list missing")
    result = {}
    for item in items:
        if not isinstance(item, dict) or not item.get(key) or item[key] in result:
            raise ValueError(f"Missing/duplicated {key}")
        result[item[key]] = item
    return result

def validate_map(mapping):
    if mapping.get("shopDomain") != STORE or mapping.get("currency") != CURRENCY:
        raise ValueError("Mapping must target indexesstore.com in YER")
    rows = mapping.get("products")
    if not isinstance(rows, list):
        raise ValueError("Mapping requires a products list")
    seen = [set(), set(), set()]
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid mapping")
        keys = ["shopifyProductGid", "shopifyVariantGid", "growerpProductId"]
        ids = [row.get(k) for k in keys]
        if not all(isinstance(x, str) and x for x in ids):
            raise ValueError("Explicit one-to-one product IDs required")
        if not ids[0].startswith("gid://shopify/Product/") or not ids[1].startswith("gid://shopify/ProductVariant/"):
            raise ValueError("Invalid Shopify GID")
        for j, ident in enumerate(ids):
            if ident in seen[j]:
                raise ValueError("Duplicate mapping is unsafe")
            seen[j].add(ident)
        if "baseline" in row and not isinstance(row["baseline"], dict):
            raise ValueError("Baseline must be previously approved values")
    return rows

def plan(shopify, growerp, mapping):
    assert_shop(shopify.get("shop"))
    rows = validate_map(mapping)
    shops, grows = index_by(shopify.get("products"), "id"), index_by(growerp.get("products"), "productId")
    proposed, blocked, warnings = [], [], []
    same = 0
    for row in sorted(rows, key=lambda x: x["shopifyProductGid"]):
        sid, vid, gid = (row[k] for k in ("shopifyProductGid", "shopifyVariantGid", "growerpProductId"))
        ref = {"shopifyProductGid": sid, "growerpProductId": gid}
        s, g = shops.get(sid), grows.get(gid)
        if s is None or g is None:
            blocked.append({**ref, "reason": "mapped_product_missing"})
            continue
        conn = s.get("variants")
        if not isinstance(conn, dict):
            blocked.append({**ref, "reason": "variants_unverified"})
            continue
        vs = conn.get("nodes")
        page = conn.get("pageInfo", {})
        if (not isinstance(vs, list) or len(vs) != 1 or
            not isinstance(page, dict) or page.get("hasNextPage") or vs[0].get("id") != vid):
            blocked.append({**ref, "reason": "multiple_or_mismatched_variants"})
            continue
        v = vs[0]
        if row.get("sku") and row["sku"] != v.get("sku"):
            blocked.append({**ref, "reason": "sku_changed"})
            continue
        qty = v.get("inventoryQuantity")
        if qty is None:
            warnings.append({**ref, "reason": "inventory_unknown"})
        elif not isinstance(qty, int) or isinstance(qty, bool):
            blocked.append({**ref, "reason": "inventory_invalid"})
            continue
        elif qty >= 10000:
            warnings.append({**ref, "reason": "high_inventory_requires_stock_count"})
        warnings.append({**ref, "reason": "inventory_mapping_to_locations_not_implemented"})
        fields = [("title", s.get("title"), g.get("productName"))]
        currency = g.get("currency")
        if not isinstance(currency, dict) or currency.get("currencyId") != CURRENCY:
            blocked.append({**ref, "field": "price", "reason": "growerp_currency_unverified"})
        else:
            try:
                fields.append(("price", money(v.get("price")), money(g.get("price"))))
            except ValueError:
                blocked.append({**ref, "field": "price", "reason": "invalid_price"})
        baseline = row.get("baseline", {})
        for field, sh, gr in fields:
            if not isinstance(sh, str) or not isinstance(gr, str) or not sh.strip() or not gr.strip():
                blocked.append({**ref, "field": field, "reason": "invalid_field"})
                continue
            if field == "price":
                sh, gr = money(sh), money(gr)
            if sh == gr:
                same += 1
                continue
            before = baseline.get(field)
            if before is not None and field == "price":
                try:
                    before = money(before)
                except ValueError:
                    before = None
            if before is None:
                blocked.append({**ref, "field": field, "reason": "no_approved_baseline"})
            elif sh == before and gr != before:
                proposed.append({**ref, "field": field, "direction": "growerp_to_shopify", "from": sh, "to": gr})
            elif gr == before and sh != before:
                proposed.append({**ref, "field": field, "direction": "shopify_to_growerp", "from": gr, "to": sh})
            else:
                blocked.append({**ref, "field": field, "reason": "concurrent_changes"})
    mapped_s = {m["shopifyProductGid"] for m in rows}
    mapped_g = {m["growerpProductId"] for m in rows}
    return {
        "mode": "READ_ONLY_PREVIEW", "writesEnabled": False,
        "shop": STORE, "currency": CURRENCY,
        "counts": {
            "shopifyProducts": len(shops), "growerpProducts": len(grows),
            "mapped": len(rows), "unmappedShopify": len(shops.keys() - mapped_s),
            "unmappedGrowerp": len(grows.keys() - mapped_g),
            "proposals": len(proposed), "blocked": len(blocked), "unchangedFields": same,
        },
        "proposedChanges": proposed, "blocked": blocked, "warnings": warnings,
    }

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mapping", required=True, type=Path)
    p.add_argument("--shopify-json", type=Path)
    p.add_argument("--growerp-json", type=Path)
    p.add_argument("--output", type=Path)
    a = p.parse_args(argv)
    if bool(a.shopify_json) != bool(a.growerp_json):
        p.error("Provide both local fixture files, or neither to read authenticated APIs")
    try:
        mapping = json.loads(a.mapping.read_text(encoding="utf-8"))
        if a.shopify_json:
            shopify = json.loads(a.shopify_json.read_text(encoding="utf-8"))
            growerp = json.loads(a.growerp_json.read_text(encoding="utf-8"))
        else:
            shopify, growerp = read_shopify(), read_growerp()
        result = json.dumps(plan(shopify, growerp, mapping), ensure_ascii=False, indent=2)
        if a.output:
            a.output.write_text(result + "\n", encoding="utf-8")
            print(f"Read-only preview written to {a.output}")
        else:
            print(result)
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as e:
        # No credentials, private HTTP payloads, or PII in failure output.
        print(f"Preview aborted: {type(e).__name__}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
