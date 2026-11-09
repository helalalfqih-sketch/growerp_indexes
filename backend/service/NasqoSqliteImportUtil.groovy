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
            Map counts = [:]
            REQUIRED.each { String table ->
                counts[table] = (selectRows(conn,
                    'SELECT COUNT(*) AS count FROM "' + table + '"')[0].count as Number).longValue()
            }
            List billTypes = selectRows(conn,
                'SELECT bill_type AS billType, bstate AS state, COUNT(*) AS count ' +
                'FROM input_move GROUP BY bill_type, bstate ORDER BY bill_type, bstate'
            ).collect { row ->
                [billType: row.billType, state: row.state, count: (row.count as Number).longValue()]
            }
            List currencies = selectRows(conn,
                'SELECT id, name, sympol, amount, amount2, name2 FROM currencies')
            List stores = selectRows(conn, 'SELECT id, name, state FROM stores')
            return [
                format: 'nasqo-sqlite-review-v1',
                readOnly: true,
                importPolicy: [
                    target: 'current-company', financialDocuments: 'draft-only',
                    postAccounting: false
                ],
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
            conn.close()
        }
    }
}

// Keep this helper name different from this Groovy script filename.
return NasqoSqliteImportPreviewHelper
