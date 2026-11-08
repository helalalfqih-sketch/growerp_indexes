import groovy.sql.Sql

/** Read-only inspection of the legacy POS SQLite backup used by NASQO import preview. */
class NasqoSqliteImportUtil {
    private static final Set<String> REQUIRED = [
        'products', 'customers', 'stores', 'currencies', 'input_move',
        'bill_details', 'bin', 'store_trans'
    ] as Set

    static Map preview(File database) {
        if (!database?.isFile() || database.length() == 0L) {
            throw new IllegalArgumentException('SQLite backup file is empty')
        }
        if (database.length() > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException('SQLite backup file exceeds the 256 MB limit')
        }

        Class.forName('org.sqlite.JDBC')
        Sql sql = Sql.newInstance("jdbc:sqlite:${database.absolutePath}")
        try {
            String integrity = sql.firstRow('PRAGMA integrity_check()')?.values()?.first()?.toString()
            if (integrity != 'ok') throw new IllegalArgumentException('SQLite integrity check failed')

            Set<String> tables = sql.rows("SELECT name FROM sqlite_master WHERE type = 'table'")
                .collect { it.name.toString() } as Set
            Set<String> missing = REQUIRED - tables
            if (missing) throw new IllegalArgumentException("Unsupported SQLite backup; missing: ${missing.sort().join(', ')}")

            Map counts = [:]
            REQUIRED.each { String table ->
                counts[table] = sql.firstRow("SELECT COUNT(*) AS count FROM \"${table}\"").count as long
            }
            List billTypes = sql.rows('''
                SELECT bill_type AS billType, bstate AS state, COUNT(*) AS count
                FROM input_move GROUP BY bill_type, bstate
                ORDER BY bill_type, bstate
            ''').collect { [billType: it.billType, state: it.state, count: it.count as long] }
            List currencies = sql.rows('SELECT id, name, sympol, amount, amount2, name2 FROM currencies')
            List stores = sql.rows('SELECT id, name, state FROM stores')
            return [
                format: 'nasqo-sqlite-review-v1',
                readOnly: true,
                importPolicy: [target: 'current-company', financialDocuments: 'draft-only', postAccounting: false],
                counts: counts,
                billTypes: billTypes,
                currencies: currencies,
                stores: stores,
                warnings: [
                    'The source has no chart of accounts or tax-rate mapping.',
                    'Financial documents remain drafts until bill-type and account mappings are approved.',
                    'Existing products and customers require duplicate matching review.'
                ]
            ]
        } finally {
            sql.close()
        }
    }
}

// Return the helper class to callers of Moqui's ec.resource.script().
return NasqoSqliteImportUtil
