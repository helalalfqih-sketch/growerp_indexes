import java.sql.Connection
import java.sql.DriverManager

/** Strictly read-only legacy SQLite preview: returns a class for ec.resource.script(). */
class NasqoSqliteImportPreviewHelper {
    private static final Set<String> REQUIRED = [
        'products', 'customers', 'stores', 'currencies', 'input_move',
        'bill_details', 'bin', 'store_trans'
    ] as Set

    private static List<Map<String, Object>> selectRows(Connection conn, String sqlText) {
        conn.createStatement().withCloseable { statement ->
            statement.executeQuery(sqlText).withCloseable { result ->
                List<Map<String, Object>> output = []
                def metadata = result.metaData
                int length = metadata.columnCount
                while (result.next()) {
                    Map<String, Object> row = [:]
                    for (int i = 1; i <= length; i++) {
                        row[metadata.getColumnLabel(i)] = result.getObject(i)
                    }
                    output << row
                }
                return output
            }
        }
    }

    static Map preview(File database) {
        if (!database?.isFile() || database.length() == 0L) {
            throw new IllegalArgumentException('SQLite backup file is empty')
        }
        if (database.length() > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException('SQLite backup file exceeds the 256 MB limit')
        }
        byte[] header = new byte[16]
        database.withInputStream { stream ->
            if (stream.read(header) != 16 || !java.util.Arrays.equals(header, [
                83,81,76,105,116,101,32,102,111,114,109,97,116,32,51,0
            ] as byte[])) {
                throw new IllegalArgumentException('Not a SQLite database')
            }
        }
        Class.forName('org.sqlite.JDBC')
        String uri = database.toURI().toString()
        Connection conn = DriverManager.getConnection('jdbc:sqlite:' + uri + '?mode=ro')
        try {
            conn.createStatement().withCloseable { it.execute('PRAGMA query_only=ON') }
            def integrity = selectRows(conn, 'PRAGMA integrity_check')
            if (!integrity || integrity[0].values().first()?.toString() != 'ok') {
                throw new IllegalArgumentException('SQLite integrity check failed')
            }
            Set<String> tables = selectRows(conn,
                "SELECT name FROM sqlite_master WHERE type = 'table'")
                .collect { it.name.toString() } as Set
            Set<String> missing = REQUIRED - tables
            if (missing) throw new IllegalArgumentException(
                'Unsupported SQLite backup; missing: ' + missing.sort().join(', '))
            // Additional known source tables are accounting evidence only.
            // None of these tables is imported into NASQO by preview.
            Map counts = [:]
            REQUIRED.each { String table ->
                counts[table] = (selectRows(conn,
                    'SELECT COUNT(*) AS count FROM "' + table + '"')[0].count as Number).longValue()
            }
            List<String> optionalAccountingTables = [
                'bill_details2', 'boxes', 'cboxes', 'macc', 'trans_details',
                'bin_bill_details', 'bin_bill_details2'
            ]
            optionalAccountingTables.each { String table ->
                counts[table] = tables.contains(table) ?
                    (selectRows(conn,
                        'SELECT COUNT(*) AS count FROM "' + table + '"')[0].count as Number).longValue()
                    : 0L
            }
            // Historical Flutter accounting setup chips expect these keys.
            // Do not conflate stock transfer lines with posted GL entries.
            counts.financialMoves = counts.input_move
            counts.billDetails = counts.bill_details
            counts.stockMoves = counts.trans_details
            counts.storeTransfers = counts.store_trans
            List billTypes = selectRows(conn,
                'SELECT bill_type AS billType, bstate AS state, COUNT(*) AS count ' +
                'FROM input_move GROUP BY bill_type, bstate ORDER BY bill_type, bstate'
            ).collect { row ->
                [billType: row.billType, state: row.state, count: (row.count as Number).longValue()]
            }
            List currencies = selectRows(conn,
                'SELECT id, name, sympol, amount, amount2, name2 FROM currencies')
            List stores = selectRows(conn, 'SELECT id, name, state FROM stores')
            List sourcePartyTypes = selectRows(conn,
                'SELECT type AS sourceType, COUNT(*) AS count FROM customers ' +
                'GROUP BY type ORDER BY type'
            ).collect { row ->
                [sourceType: row.sourceType, count: (row.count as Number).longValue()]
            }
            // A financial movement is a source document, not an AcctgTrans entry.
            // Values called dain/madin have NOT been approved as debit/credit.
            List unsupportedCurrencyRefs = selectRows(conn,
                'SELECT COUNT(*) AS count FROM input_move m ' +
                'LEFT JOIN currencies c ON c.id = m.cur ' +
                'WHERE m.cur IS NOT NULL AND c.id IS NULL'
            )
            long unmatchedCurrencyRefs =
                (unsupportedCurrencyRefs[0].count as Number).longValue()
            List<String> pending = [
                'Approve meanings of all source bill_type and bstate codes',
                'Approve customer/vendor role mapping and duplicate matching',
                'Approve source currency, exchange rates, and tax treatment',
                'Approve opening balances, GL account mapping, and cashboxes',
                'Resolve unmatched references before any accounting import'
            ]
            return [
                format: 'nasqo-sqlite-review-v1',
                readOnly: true,
                importPolicy: [
                    target: 'current-company-accounting-only',
                    productCatalogImport: false,
                    globalApplicationImport: false,
                    financialDocuments: 'blocked-pending-mapping',
                    postAccounting: false
                ],
                counts: counts,
                billTypes: billTypes,
                currencies: currencies,
                stores: stores,
                accountingReview: [
                    targetModule: 'accounting',
                    sourceAccountingMovements: counts.input_move,
                    sourcePrimaryBillLines: counts.bill_details,
                    sourceSupplementaryBillLines: counts.bill_details2,
                    sourceCashboxes: counts.boxes + counts.cboxes,
                    sourceChartOfAccountsRows: counts.macc,
                    sourcePartyTypes: sourcePartyTypes,
                    unclassifiedBillTypes: billTypes.collect { it.billType }.unique(),
                    unmatchedMovementCurrencyRefs: unmatchedCurrencyRefs,
                    accountingPostingAllowed: false,
                    automaticLedgerConversionAllowed: false,
                    fullApplicationImportAllowed: false,
                    readyForAccountingImport: false,
                    requiredApprovals: pending
                ],
                warnings: [
                    'Accounting module ONLY: products and stores are references, not app-wide imports.',
                    'No verified source chart of accounts or tax-rate mapping is available.',
                    'Do not interpret source dain/madin as ledger debit/credit.',
                    'Bills and balances cannot be posted before type, currency, tax and account mappings are approved.',
                    'Existing accounting entries require duplicate and opening-balance reconciliation.'
                ]
            ]
        } finally {
            conn.close()
        }
    }
}

// Keep this helper name different from this Groovy script filename.
return NasqoSqliteImportPreviewHelper
