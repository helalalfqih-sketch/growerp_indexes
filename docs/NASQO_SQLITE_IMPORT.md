# NASQO SQLite Import

The legacy `.back` file is a SQLite POS database, not a NASQO/Moqui owner-data
ZIP. The first migration step is deliberately read-only and produces a review
report; it does not create records or post accounting entries.

```bash
python3 tools/import_nasqo_sqlite_backup.py \
  backup_2026.11.08_03.49.26.back \
  --output /tmp/nasqo-sqlite-review
```

The report contains normalized products, customers, financial moves, source
bill types, store data, and warnings. Review `import_report.json` before the
application importer is enabled.

## Required approval before application import

- Confirm the target company.
- Map every source `bill_type` to a NASQO document type.
- Confirm the source currency and tax treatment.
- Map sales, purchase, payment, inventory, and discount accounts.
- Keep imported financial documents as drafts until reconciliation is complete.
- Never treat `dain` and `madin` as balanced accounting debit/credit values
  without a source-system definition; the legacy backup contains rows where
  both fields have the same value.

The original SQLite file must remain outside Git and must be preserved as the
source artifact. The next application phase can upload the file to a staging
job, run this report server-side, show the mapping preview, and only then
create NASQO draft documents.