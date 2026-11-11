# Shopify and GrowERP bidirectional preview (READ ONLY)

Shopify store: indexesstore.com. Verified Shopify Admin domain:
ubhd8d-iz.myshopify.com. Currency: YER.

This folder contains a read-only integration preview, NOT a running live
synchronization. No code can add, delete or update Shopify or GrowERP data.

## Supported now

1. Paginated Shopify Admin GraphQL read of products, variants, prices and
   inventory estimates (requires a private, scoped read_products token).
2. Paginated GrowERP Products REST read using an HTTPS test instance and a
   read-only GrowERP API key.
3. Explicit mapping of Shopify product GID + variant GID to GrowERP productId.
   Never automatically match by SKU or product name; the GrowERP Product
   model has no native Shopify SKU/variant identity.
4. Price and title differences classified as Shopify-to-GrowERP,
   GrowERP-to-Shopify or BLOCKED. A per-field last-approved baseline is
   required to infer the change direction. No baseline or changes on both
   systems are conflicts and require a human decision.
5. Guardrails for wrong shop, wrong currency, multiple variants, changed SKU,
   incomplete pagination, invalid prices and high Shopify stock counts.

Inventory is NOT synchronized: the GrowERP Product REST model does not expose
a Shopify-style location/variant inventory item. Quantities of 10,000 or more
are flagged rather than assumed to be physical stock. Orders, customers,
taxes, invoices, refunds and shipping are out of scope for this first step.

## Test without any credentials

Run from repository root:

    python3 -m unittest discover -s integrations/shopify_growerp -p 'test_*.py' -v

Tests use fictional products and mock network calls. A GitHub Actions workflow
runs the tests for pull requests.

## Private read-only live preview (no writes)

Make an untracked private mapping JSON with the following structure:

    {
      "shopDomain": "ubhd8d-iz.myshopify.com",
      "currency": "YER",
      "products": [{
        "shopifyProductGid": "gid://shopify/Product/101",
        "shopifyVariantGid": "gid://shopify/ProductVariant/201",
        "growerpProductId": "P1",
        "sku": "DEMO",
        "baseline": {"title": "Demo", "price": "100.00"}
      }]
    }

All IDs above are fictional. A baseline MUST be genuinely verified and
approved from a prior snapshot of both systems. When no prior snapshot exists,
omit baseline: differing fields will be blocked, not guessed.

Set credentials only in a private secret manager or local environment:

    SHOPIFY_SHOP_DOMAIN=ubhd8d-iz.myshopify.com
    SHOPIFY_API_VERSION=2026-10
    SHOPIFY_ADMIN_TOKEN=(read-only Shopify app token)
    GROWERP_BASE_URL=https://your-growerp-test-host
    GROWERP_API_KEY=(read-only test account key)

Choose an API version actually supported by your Shopify store; the example
version above must be verified before running. Do not paste credentials in chat
or commit any private snapshots or mappings to a public GitHub repository.

Then run:

    python3 integrations/shopify_growerp/preview.py \
      --mapping /private/mapping.json --output /private/preview.json

No historical baseline, HTTPS GrowERP test endpoint, mapping or credentials
are present in this repository. The ChatGPT Shopify connection does NOT hand
its private token to an independent integration script.

## Before a genuine bidirectional sync

- Test an isolated GrowERP company and an authorized Shopify test environment.
- Verify identities and mappings, multiple variants, taxes, currency, warehouse
  locations, nonphysical stock values and customer/order access controls.
- Design a SEPARATE write executor with explicit approval, minimum scopes,
  idempotency, version/concurrency checks, audit log, retries, pagination,
  rate limiting, reconciliation and rollback.
- Inventory sync must first map Shopify location IDs to GrowERP warehouse
  facilities and determine which system owns stock changes.
- Order imports must map taxes, payment status, returns, refunds and
  inventory deduction without double-posting accounting entries.
- Never enable real writes based on this preview alone.
