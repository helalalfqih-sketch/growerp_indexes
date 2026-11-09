import java.sql.Connection
import java.sql.DriverManager
import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant

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
            // boxes are user-defined account groups, referenced by customers.type.
            // Only cboxes contains cashboxes. macc belongs to the measurements module.
            List sourceAccountGroups = []
            if (tables.contains('boxes')) {
                Set groupColumns = selectRows(conn, 'PRAGMA table_info(boxes)')
                    .collect { it.name.toString() } as Set
                if (groupColumns.containsAll(['id', 'name'])) {
                    sourceAccountGroups = selectRows(conn,
                        'SELECT b.id AS sourceType, b.name AS sourceGroupName, ' +
                        'COUNT(c.id) AS accountCount FROM boxes b ' +
                        'LEFT JOIN customers c ON c.type=b.id GROUP BY b.id,b.name ORDER BY b.id')
                    Map names = sourceAccountGroups.collectEntries {
                        [(it.sourceType.toString()): it.sourceGroupName]
                    }
                    sourcePartyTypes.each { it.sourceGroupName = names[it.sourceType?.toString()] }
                }
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
                    sourceCashboxes: counts.cboxes,
                    sourceAccountGroupCount: counts.boxes,
                    sourceAccountGroups: sourceAccountGroups,
                    sourceAccountRecordCount: counts.customers,
                    // Unknown is not zero: this export has no verified NASQO-style GL chart.
                    sourceChartOfAccountsRows: null,
                    sourceMeasurementAccounts: counts.macc,
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
                    'Source account groups (boxes) and account records (customers) require GL mapping; macc is a measurements table.',
                    'No verified tax-rate mapping is available.',
                    'مجموعات الحسابات منفصلة عن الصناديق النقدية؛ لا يعني فراغ جدول المقاسات غياب الحسابات.',
                    'Do not interpret source dain/madin as ledger debit/credit.',
                    'Bills and balances cannot be posted before type, currency, tax and account mappings are approved.',
                    'Existing accounting entries require duplicate and opening-balance reconciliation.'
                ]
            ]
        } finally {
            conn.close()
        }
    }

    /**
     * Preserve an explicitly approved, authenticated company source for review.
     *
     * This is NOT an accounting import: no PostgreSQL access, invoices, payments,
     * GL journals, account creation, or ledger posting occur here. One source
     * fingerprint per company prevents accidental double-import preparation.
     * The persistent root is an operator-controlled private Docker bind mount.
     */
    static Map stageForAccounting(File source, File privateRoot, String companyId) {
        if (companyId == null || !(companyId ==~ /[A-Za-z0-9_-]{1,64}/)) {
            throw new IllegalArgumentException('A validated company is required for staging')
        }
        if (source == null || !source.isFile() || source.length() < 100L ||
                source.length() > 32L * 1024L * 1024L) {
            throw new IllegalArgumentException('Accounting staging accepts SQLite files up to 32 MiB')
        }
        if (privateRoot == null || !privateRoot.isDirectory() ||
                Files.isSymbolicLink(privateRoot.toPath())) {
            throw new IllegalStateException('Private accounting staging storage is not mounted')
        }
        Path rootPath = privateRoot.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        Path ownerPath = rootPath.resolve(companyId)
        if (Files.isSymbolicLink(ownerPath)) {
            throw new IllegalStateException('Unsafe company staging path')
        }
        if (!Files.exists(ownerPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(ownerPath)
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A simultaneous request may create the same isolated directory.
            }
        }
        if (!Files.isDirectory(ownerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException('Invalid company staging directory')
        }
        Files.setPosixFilePermissions(ownerPath, PosixFilePermissions.fromString('rwx------'))
        // Re-validate the actual input without ever connecting to NASQO PostgreSQL.
        Map reviewed = preview(source)
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        source.withInputStream { input ->
            byte[] chunk = new byte[64 * 1024]
            int amount
            while ((amount = input.read(chunk)) != -1) {
                digest.update(chunk, 0, amount)
            }
        }
        String sha = digest.digest().collect { String.format('%02x', it & 0xff) }.join()
        Path finalPath = ownerPath.resolve(sha)
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(finalPath) ||
                    !Files.isRegularFile(finalPath.resolve('source.back'), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException('Existing source staging is incomplete')
            }
            // Never silently accept a same-name or previously changed source.
            MessageDigest verify = MessageDigest.getInstance('SHA-256')
            finalPath.resolve('source.back').toFile().withInputStream { input ->
                byte[] chunk = new byte[64 * 1024]
                int amount
                while ((amount = input.read(chunk)) != -1) verify.update(chunk, 0, amount)
            }
            String storedSha = verify.digest().collect { String.format('%02x', it & 0xff) }.join()
            if (storedSha != sha) {
                throw new IllegalStateException('Stored staging source failed integrity verification')
            }
            return [
                status: 'REVIEW_ONLY_NOT_IMPORTED', stageId: sha.substring(0, 16),
                sourceSha256: sha, existing: true, targetCompanyPartyId: companyId,
                sourceRowsWrittenToAccounting: 0, postedEntries: 0
            ]
        }
        File[] previouslyStaged = ownerPath.toFile().listFiles({ File f ->
            f.isDirectory() && (f.name ==~ /[a-f0-9]{64}/)
        } as FileFilter)
        if (previouslyStaged == null) {
            throw new IllegalStateException('Unable to inspect private company staging')
        }
        if (previouslyStaged.length > 0) {
            throw new IllegalStateException('An accounting source is already staged for this company; reconciliation is required before replacing it')
        }
        Path pending = Files.createTempDirectory(ownerPath, '.pending-')
        try {
            Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString('rwx------'))
            Path saved = pending.resolve('source.back')
            Files.copy(source.toPath(), saved)
            Files.setPosixFilePermissions(saved, PosixFilePermissions.fromString('r--------'))
            Map manifest = [
                format: 'nasqo-smart-accountant-staging-v1',
                status: 'BLOCKED_PENDING_ACCOUNT_MAPPING',
                sourceType: 'SMART_ACCOUNTANT_DESKTOP_TO_ANDROID_SQLITE_EXPORT',
                sourceSha256: sha,
                sourceSizeBytes: source.length(),
                targetModule: 'accounting',
                targetCompanyPartyId: companyId,
                createdAt: Instant.now().toString(),
                sourceTableCounts: reviewed.counts,
                sourceBillTypeAndStateCounts: reviewed.billTypes,
                sourceCurrencyCount: reviewed.currencies.size(),
                sourcePartyTypeCounts: reviewed.accountingReview.sourcePartyTypes,
                unmatchedMovementCurrencyRefs: reviewed.accountingReview.unmatchedMovementCurrencyRefs,
                requiredApprovals: reviewed.accountingReview.requiredApprovals,
                postAccounting: false,
                sourceRowsWrittenToAccounting: 0,
                postedEntries: 0,
                applicationWideImport: false
            ]
            Path manifestPath = pending.resolve('manifest.json')
            Files.write(manifestPath, (JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + '\n')
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString('r--------'))
            Files.move(pending, finalPath, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            // A successful atomic move makes pending disappear.
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(pending.resolve('source.back'))
                Files.deleteIfExists(pending.resolve('manifest.json'))
                Files.deleteIfExists(pending)
            }
        }
        return [
            status: 'REVIEW_ONLY_NOT_IMPORTED', stageId: sha.substring(0, 16),
            sourceSha256: sha, existing: false, targetCompanyPartyId: companyId,
            sourceRowsWrittenToAccounting: 0, postedEntries: 0
        ]
    }

}

// Keep this helper name different from this Groovy script filename.
return NasqoSqliteImportPreviewHelper
