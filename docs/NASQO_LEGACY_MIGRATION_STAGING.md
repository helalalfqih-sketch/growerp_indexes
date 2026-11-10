# NASQO ERP — legacy SQLite staging and manual mapping

**Status: review-only toolkit. This is NOT a production importer, data restore, or
approval to post financial entries.** A .back file is an SQLite POS database,
NOT an OwnerData ZIP. Do not upload it through OwnerData/Import.

## What this PR actually implements

- Open an original backup using SQLite read-only/immutable mode and validate
  its SQLite header, integrity, required tables and source columns.
- Enumerate **every source table** with row counts and a declared review scope.
  An unexpected table is flagged as UNKNOWN_REQUIRES_MAPPING, never discarded.
- Produce a private manifest.json showing data-quality checks and unresolved
  foreign references, and a mapping_template.json with **no approved mapping**.
- Optionally create complete raw JSONL snapshots of all source tables locally,
  plus product, party, location and **blocked** financial candidates. This
  data is sensitive and must stay in an access-restricted encrypted location.
- Keep the original .back unmodified. No NASQO REST API requests, database
  writes, financial postings, merchant integration, or production deployment.

## Data mapping (proposed, not approved)

| Legacy source | NASQO destination to verify | Gate |
|---|---|---|
| products (including units and prices) | Product / units / price lists | Validate units, currency, matching, and duplicates |
| customers (including source type) | Party with authorized customer/supplier role | Confirm source role codes, contacts, duplicates |
| stores | Inventory Location / warehouse | Confirm branch/location model and unresolved IDs |
| currencies | Company currency and rates | Confirm currency codes and exchange treatment |
| input_move + bill_details + bill_details2 | FinDoc / payment / related draft lines | Approve all bill_type meanings and period/date, tax and accounts |
| store_trans + trans_details | Inventory transfers and draft lines | Resolve source/target stores and quantities |
| boxes + cboxes | Cashbox/cash account configuration | Manual business definition and account mapping |
| bin + bin_bill_details + bin_bill_details2 | Archived/voided-document review | Preserve archive, do not re-post voided records |
| offers + offer_bill_details + offer_sbill_details | Quote / offer candidates | Review promotion/quote semantics |
| macc, meters, meters_move, shtable, alerts | Domain-specific/auxiliary review | Inspect before deciding target model |

Where a legacy table has zero records, keep its scope in the manifest anyway.
The source column names dain and madin remain **raw fields**; they are not safe
debit and credit definitions. A bill_type integer is **not** a confirmed sale
or purchase. A movement without rows in bill_details may legitimately be
a different movement kind.

## Usage (offline only)

Only point this program at an authorized copy of the backup:

    python3 tools/nasqo_legacy_staging.py /private/original.back \
      --output /private/nasqo-review-001

This creates a sanitized metadata review and blank mapping form. It does **not**
copy any individual product, customer or invoice rows into the output.

For an explicitly private, local record-level staging bundle, use a NEW directory
on an encrypted device that only the authorized reviewer can access:

    python3 tools/nasqo_legacy_staging.py /private/original.back \
      --output /private/nasqo-review-002 --stage-records

The stage-records option creates source_tables/*.jsonl for every table and
separate candidate JSONL files. Raw JSONL contains PII, contacts, prices and
financial information. Never commit, share, email, or put these in CI artifacts.
On Windows, rely on reviewed NTFS permissions and disk encryption; Python
POSIX file modes alone are not adequate Windows access control.
Never execute the staging tool with an output path inside the Git checkout.

The tool refuses to overwrite an existing output directory. The report includes
a source SHA-256 fingerprint for stable source references. The original .back
remains the canonical backup.

## Required approvals for the next implementation phase

1. Confirm the exact NASQO destination company and sandbox tenant identity.
2. Confirm which customers.type values represent customer, supplier or other
   roles; reconcile duplicate products/parties by explicit source ID.
3. Approve product units, multipliers, inventory location mapping, and one
   source currency to one target currency, with treatment of exchange rates.
4. Approve **every** bill type, sign, state and cancellation/return rule.
5. Provide an accountant-approved chart of accounts, tax handling, opening
   balances, and a source-to-target reconciliation method. An empty source
   account table cannot substitute for a valid chart.
6. Resolve missing source references and review archive/offer/sector records.
7. Build a **separate**, authenticated destination adapter with staging-only
   tenant isolation, idempotency keys (sourceHash:table:sourceId), strict
   reference matching, and the ability to resume safely. Do not create a
   production write path until that adapter has security tests.
8. Reconcile counts, values, invoice lines, currency and source IDs; have a
   financial reviewer approve results. Keep documents unposted, with approval
   separate from draft creation.

## Tests and boundaries

    python3 tools/test_nasqo_legacy_staging.py
    python3 tools/test_import_nasqo_sqlite_backup.py

CI uses **only synthetic SQLite fixtures**, never customer backups. Its passing
state certifies the staging code paths and isolation decisions, **not** Moqui
integration or a completed migration. The final import must first run in an
isolated Moqui/PostgreSQL staging environment with synthetic data, and only
then run against authorized source data after the above approvals. Never
merge this PR as proof of production migration readiness.


## Company identity check before any staging migration (read-only)

The target company name and numeric ID must be matched against an authenticated
NASQO Moqui PartyServices100.get#Company response **in the intended environment**.
Use the internal JSON field partyId; pseudoId is not interchangeable. A
Shopify store or public URL does not prove the NASQO company identity.
Independent staging/production databases may have different party IDs.

Record the candidate in your private mapping_template.json as
targetCompanyPartyIdCandidate, but retain targetCompanyPartyId=null,
targetCompanyVerified=false, approved=false. Add authorized spelling aliases
under targetCompanyAliases when necessary. Do not commit the mapping file.

Without authenticated staging access, the following **does not make a network
request** and reports a blocked identity status:

    python3 tools/nasqo_staging_company_identity.py \
      --mapping /private/review/mapping_template.json \
      --manifest /private/review/manifest.json \
      --source /private/original.back

A previously saved Company API response JSON can be checked as an offline
fixture with --response-file, but this never proves live company identity.

Only when an authorized staging backend exists, configure a **read-only**
Moqui login key securely in the NASQO_STAGING_API_KEY environment variable
(never place the key in chat, CLI arguments, Git or logs) and run:

    python3 tools/nasqo_staging_company_identity.py \
      --mapping /private/review/mapping_template.json \
      --manifest /private/review/manifest.json \
      --source /private/original.back \
      --staging-base-url https://staging.example.test

This verifier performs a single authenticated GET using the
Company?companyPartyId=<candidate>&limit=2 service path. It disables HTTP
redirects, validates HTTPS and a staging/test host label, and prints only a
sanitized match decision. It **never** writes a company, changes the mapping
approval, creates drafts, or posts ledger entries.

### Staging acceptance gates (still pending)

- Provision a separate, durable Moqui/PostgreSQL staging environment; never
  point a migration script at the production database.
- Authenticate as an authorized tester and independently verify company
  partyId, pseudoId, name and owning tenant in that specific environment.
- Rehearse an end-to-end synthetic-only import and rollback before real data.
- Require approved bill-type meanings, party roles, taxes, units, currencies,
  account mapping and source-to-target idempotency before real draft migration.
- Reconcile original rows and preserve unapproved/missing references for review.
- Never mark a financial document posted as a side-effect of bulk import.

If a staging backend is unavailable, the identity gate must remain blocked
with PENDING_AUTHENTICATED_STAGING_LOOKUP; a user-supplied ID is not proof.


## Disposable PostgreSQL CI rehearsal (synthetic data only)

Run:

    python3 tools/test_nasqo_ephemeral_pg_rehearsal.py

GitHub Actions provisions a fresh PostgreSQL 17.2 service named
nasqo_ci_disposable (CI-only credentials, not a real tenant). The Python
rehearsal requires an explicit opt-in flag, an SQLite filename beginning
with synthetic_, and a table containing a hard-coded synthetic marker.
It rejects remote hosts, production database names, non-CI usernames and
unexpected source sizes.

Inside one database transaction, it:

1. Reads each synthetic SQLite table except the marker table.
2. Computes per-row SHA-256 digests and stable source row identifiers;
   raw names, phone numbers, prices and financial amounts are NOT sent
   to PostgreSQL.
3. Inserts metadata into a PostgreSQL TEMP table only, marking every
   financial candidate BLOCKED_FINANCIAL_MAPPING.
4. Checks source row coverage and verifies that no row was posted or
   connected to a real company.
5. Performs an explicit ROLLBACK and confirms the TEMP table is gone.

This is an **isolated PostgreSQL transaction test**, not a Moqui service
integration test, tenant setup, real-company import or production release.
Do not run it on the NASQO production VPS, which serves active workloads.
The source legacy .back is never included in GitHub Actions or uploads.

The latest passing GitHub Actions evidence for this feature can be found
under this PR's NASQO legacy SQLite staging workflow. Keep this draft PR
open until the real target/tenant isolation and finance approval gates
are independently reviewed.


## Review candidate field mapping against actual NASQO Flutter models

A new CLI prepares **review-only** candidates using known model field names,
without guessing prices or financial signs. Run it only after creating a
private source-matched mapping template with the preceding staging tool.

First, in a PRIVATE local directory outside the Git checkout, run:

    python3 tools/nasqo_legacy_staging.py /private/original.back \
      --output /private/nasqo-review-initial

This creates an aggregate manifest and mapping_template.json containing
the SHA-256 of the exact source file. The mapping's targetCompanyPartyId and
targetCompanyVerified must remain null/false; a production partyId is NOT an
isolated staging company identity.

Then, for a metadata-only profile of model candidates:

    python3 tools/nasqo_prepare_review_candidates.py /private/original.back \
      --mapping /private/nasqo-review-initial/mapping_template.json \
      --output /private/nasqo-model-summary

The default output is only review_manifest.json; there is NO PII row export.

To opt in to reviewing individual records in a new, secure directory
(contains personal data; DO NOT commit, email or store on a public CI runner):

    python3 tools/nasqo_prepare_review_candidates.py /private/original.back \
      --mapping /private/nasqo-review-initial/mapping_template.json \
      --output /private/nasqo-model-private \
      --include-private-records

The local files are:
- product_candidates.jsonl
- party_candidates.jsonl
- location_candidates.jsonl
- financial_holds.jsonl
- review_manifest.json

No file is a ready-to-POST REST payload. The known model mapping is:
- SQLite products.name -> Product.productName and barcode -> Product.barcode.
  Product.price, Product.listPrice, Product.currency, Product.amountUom
  and productTypeId remain null until approved. Source price/price2 are
  retained as distinct raw fields, NOT presumed to be valid final prices.
- SQLite customers.name -> Company.name, while role and telephoneNr remain
  unset pending review of the 11 source party-type codes and contact rules.
- SQLite stores.name -> Location.locationName, while locationId and branch
  membership remain unset pending 5-location validation.
- SQLite input_move -> BLOCKED_FINANCIAL_MAPPING metadata. FinDoc.docType
  and grandTotal remain null; isPosted is always false. Bill details are
  only counted, never auto-posted or reinterpreted as balanced ledger entries.

All records have stable source refs incorporating the complete source
SHA-256 and original table/row ID for later reconciled mapping. The tool
refuses a mismatched backup hash, approved/auto-post mappings, a staging
company identity asserted without validation, or overwriting a review folder.

Both the default and opt-in modes are strictly offline. Candidate creation
must not be described as completed data import to NASQO. For any user data,
verify local disk encryption and access controls outside this Python CLI.

Run synthetic-only regression tests:

    python3 tools/test_nasqo_prepare_review_candidates.py

CI executes those tests alongside the independent synthetic PostgreSQL
rollback job. Staging Moqui writes and accountant-approved business
reconciliation are still separate, blocked deliverables.


## Aggregate data reconciliation gate (read-only)

The reconciliation CLI is available for a private source copy:

    python3 tools/nasqo_migration_reconciliation.py /private/original.back \
      --output /private/nasqo-reconciliation.json

It **never emits names, phone numbers, addresses, prices or invoice rows**.
Only row counts, SHA-256 source fingerprint, numeric source type codes,
unmatched reference counts and blocked financial status are recorded.
The output path must be outside the Git repository, and overwrite is refused.

The check counts movements per source bill_type and compares main bill-line
coverage with supplementary bill-line coverage. It separately handles rows
with both kinds of lines so those do not inflate a missing-lines number.
It also reports declared FK violations, multi-unit product counts, product
name collision groups and source party role codes as **unclassified**.

No target NASQO database or API is contacted. Equal dain/madin numbers do
not prove balanced accounting entries. The output always sets
dataCanBeImportedToProduction=false, financialPostingAllowed=false,
destinationWrites=0 and accountingReconciliationApproved=false.

Use synthetic fixtures in CI:

    python3 tools/test_nasqo_migration_reconciliation.py

This supplements, but cannot replace, a source-system accountant-approved
bill-type lookup, owner-tenant mapping, or a true Moqui staging integration
test with rollback and private backup evidence.
