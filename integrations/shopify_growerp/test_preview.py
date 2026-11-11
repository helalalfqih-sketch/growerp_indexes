"""Offline-only safety and direction tests for the Shopify/GrowERP preview."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

path = Path(__file__).parent / "preview.py"
spec = importlib.util.spec_from_file_location("shopify_growerp_preview", path)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SID = "gid://shopify/Product/101"
VID = "gid://shopify/ProductVariant/201"
GID = "P1"

def sample(stitle="صنف", gtitle="صنف", sprice="100.00", gprice="100",
           baseline=None, qty=10000, currency="YER"):
    shop = {"shop": {"myshopifyDomain": mod.STORE, "currencyCode": "YER"},
            "products": [{"id": SID, "title": stitle,
                          "variants": {"nodes": [{"id": VID, "sku": "IDX-1",
                                                 "price": sprice, "inventoryQuantity": qty}],
                                       "pageInfo": {"hasNextPage": False}}}]}
    grow = {"products": [{"productId": GID, "productName": gtitle,
                          "price": gprice, "currency": {"currencyId": currency}}]}
    entry = {"shopifyProductGid": SID, "shopifyVariantGid": VID,
             "growerpProductId": GID, "sku": "IDX-1"}
    if baseline is not None:
        entry["baseline"] = baseline
    mapping = {"shopDomain": mod.STORE, "currency": "YER", "products": [entry]}
    return shop, grow, mapping

class PreviewTests(unittest.TestCase):
    def test_identical_decimals_are_not_conflicts(self):
        a, b, c = sample()
        p = mod.plan(a, b, c)
        self.assertFalse(p["writesEnabled"])
        self.assertEqual(p["proposedChanges"], [])
        self.assertEqual(p["counts"]["unchangedFields"], 2)

    def test_shopify_new_title_proposes_growerp_target(self):
        a, b, c = sample(stitle="صنف جديد", baseline={"title": "صنف", "price": "100"})
        x = mod.plan(a, b, c)["proposedChanges"]
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0]["direction"], "shopify_to_growerp")
        self.assertEqual(x[0]["field"], "title")

    def test_growerp_new_price_proposes_shopify_target(self):
        a, b, c = sample(gprice="125", baseline={"title": "صنف", "price": "100"})
        x = mod.plan(a, b, c)["proposedChanges"]
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0]["direction"], "growerp_to_shopify")
        self.assertEqual(x[0]["to"], "125")

    def test_two_changes_are_not_overwritten(self):
        a, b, c = sample(sprice="110", gprice="120", baseline={"title": "صنف", "price": "100"})
        p = mod.plan(a, b, c)
        self.assertEqual(p["proposedChanges"], [])
        self.assertIn("concurrent_changes", [z["reason"] for z in p["blocked"]])

    def test_no_approved_baseline_is_a_blocker(self):
        a, b, c = sample(stitle="مختلف")
        p = mod.plan(a, b, c)
        self.assertEqual(p["proposedChanges"], [])
        self.assertIn("no_approved_baseline", [z["reason"] for z in p["blocked"]])

    def test_missing_growerp_product_is_blocked(self):
        a, b, c = sample()
        b["products"] = []
        p = mod.plan(a, b, c)
        self.assertIn("mapped_product_missing", [z["reason"] for z in p["blocked"]])

    def test_mapping_duplicates_are_rejected(self):
        a, b, c = sample()
        c["products"].append(c["products"][0].copy())
        with self.assertRaises(ValueError):
            mod.plan(a, b, c)

    def test_wrong_shop_is_rejected(self):
        a, b, c = sample()
        a["shop"]["myshopifyDomain"] = "other.myshopify.com"
        with self.assertRaises(ValueError):
            mod.plan(a, b, c)

    def test_wrong_currency_is_rejected(self):
        a, b, c = sample()
        a["shop"]["currencyCode"] = "USD"
        with self.assertRaises(ValueError):
            mod.plan(a, b, c)

    def test_multiple_variants_blocked(self):
        a, b, c = sample()
        a["products"][0]["variants"]["pageInfo"]["hasNextPage"] = True
        p = mod.plan(a, b, c)
        self.assertIn("multiple_or_mismatched_variants", [z["reason"] for z in p["blocked"]])

    def test_sku_mismatch_blocked(self):
        a, b, c = sample()
        a["products"][0]["variants"]["nodes"][0]["sku"] = "OTHER"
        p = mod.plan(a, b, c)
        self.assertIn("sku_changed", [z["reason"] for z in p["blocked"]])

    def test_currency_not_verified_blocks_price_proposal(self):
        a, b, c = sample(sprice="120", currency="USD", baseline={"title": "صنف", "price": "100"})
        p = mod.plan(a, b, c)
        self.assertFalse(any(z["field"] == "price" for z in p["proposedChanges"]))
        self.assertIn("growerp_currency_unverified", [z["reason"] for z in p["blocked"]])

    def test_large_stock_warning_not_sync_action(self):
        a, b, c = sample()
        p = mod.plan(a, b, c)
        self.assertIn("high_inventory_requires_stock_count", [z["reason"] for z in p["warnings"]])
        self.assertFalse(any(z["field"] == "inventory" for z in p["proposedChanges"]))

    def test_unmapped_product_is_not_created(self):
        a, b, c = sample()
        a["products"].append({"id": "gid://shopify/Product/999", "title": "Unmapped"})
        p = mod.plan(a, b, c)
        self.assertEqual(p["counts"]["unmappedShopify"], 1)
        self.assertEqual(p["proposedChanges"], [])

    def test_price_rejects_invalid_values(self):
        for value in ("NaN", "Infinity", "-1", None, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                mod.money(value)

    def test_shopify_reads_only_the_fixed_query(self):
        a, _, _ = sample()
        with patch.dict(os.environ, {"SHOPIFY_ADMIN_TOKEN": "fake-test-only",
                                      "SHOPIFY_API_VERSION": "2026-10",
                                      "SHOPIFY_SHOP_DOMAIN": mod.STORE}):
            def mock_request(url, headers, query=None):
                self.assertTrue(url.startswith("https://" + mod.STORE))
                self.assertIn("query Preview", query["query"])
                self.assertNotIn("mutation", query["query"].lower())
                return {"data": {"shop": a["shop"], "products": {
                    "nodes": a["products"], "pageInfo": {"hasNextPage": False}}}}
            with patch.object(mod, "request_json", side_effect=mock_request):
                self.assertEqual(len(mod.read_shopify()["products"]), 1)

    def test_growerp_uses_only_a_get_request(self):
        with patch.dict(os.environ, {"GROWERP_BASE_URL": "https://test.example.org",
                                      "GROWERP_API_KEY": "fake-test-only"}):
            def mock_request(url, headers, query=None):
                self.assertIsNone(query)
                self.assertIn("/rest/s1/growerp/100/Products?", url)
                self.assertEqual(headers["api_key"], "fake-test-only")
                return {"products": [{"productId": GID}]}
            with patch.object(mod, "request_json", side_effect=mock_request):
                self.assertEqual(len(mod.read_growerp()["products"]), 1)

    def test_insecure_backend_is_rejected(self):
        with patch.dict(os.environ, {"GROWERP_BASE_URL": "http://test.example.org",
                                      "GROWERP_API_KEY": "fake"}):
            with self.assertRaises(ValueError):
                mod.read_growerp()

if __name__ == "__main__":
    unittest.main()
