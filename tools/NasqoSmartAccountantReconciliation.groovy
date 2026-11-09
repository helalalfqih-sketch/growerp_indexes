import java.sql.DriverManager
import java.security.MessageDigest
import java.time.LocalDate
import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Read-only Smart Accountant source reconciliation. No Moqui entity/service access.
 * Source-side classifications are evidence, NEVER an approved GL mapping.
 * Account keys survive re-export; fingerprints flag changed records for later review.
 */
class NasqoSmartAccountantReconciliation {
    static String digest(File f) {
        def md=MessageDigest.getInstance('SHA-256')
        f.withInputStream { s -> byte[] b=new byte[65536]; int n
            while((n=s.read(b)) != -1) md.update(b,0,n)
        }
        md.digest().encodeHex().toString()
    }
    static String hashText(String s) {
        MessageDigest.getInstance('SHA-256').digest(s.getBytes('UTF-8')).encodeHex().toString()
    }
    static String norm(Object s) { (s ?: '').toString().trim().replaceAll(/\s+/, ' ') }
    static BigDecimal amount(Object s) {
        String v=s == null ? '' : s.toString().trim()
        if (!(v ==~ /[+-]?\d+(\.\d+)?/)) throw new IllegalArgumentException('Invalid decimal')
        new BigDecimal(v)
    }
    static List rows(def c,String sql) {
        c.createStatement().withCloseable { st ->
            st.executeQuery(sql).withCloseable { rs ->
                def result=[]
                while(rs.next()) {
                    def row=[:]
                    (1..rs.metaData.columnCount).each { row[rs.metaData.getColumnLabel(it)]=rs.getObject(it) }
                    result << row
                }
                result
            }
        }
    }
    static Map review(File source,String company) {
        if (!(company ==~ /[A-Za-z0-9_-]{1,64}/)) throw new IllegalArgumentException('Invalid company')
        if (!source.isFile() || source.length()>32L*1024*1024) throw new IllegalArgumentException('Invalid source')
        String sha=digest(source)
        Class.forName('org.sqlite.JDBC')
        def c=DriverManager.getConnection('jdbc:sqlite:'+source.toURI()+'?mode=ro')
        try {
            c.createStatement().withCloseable { it.execute('PRAGMA query_only=ON') }
            assert rows(c,'PRAGMA integrity_check')[0].values().first() == 'ok'
            def groups=rows(c,'SELECT id,name FROM boxes ORDER BY id')
            def cashboxes=rows(c,'SELECT id,name,cur FROM cboxes ORDER BY id')
            def currencies=rows(c,'SELECT id,name,amount,amount2 FROM currencies ORDER BY id')
            def accounts=rows(c,'SELECT id,name,type FROM customers ORDER BY id')
            def moves=rows(c,'SELECT * FROM input_move ORDER BY id')
            def accountsById=accounts.collectEntries { [(it.id):it] }
            def groupIds=groups*.id as Set
            def currencyIds=currencies*.id as Set
            def cashboxIds=cashboxes*.id as Set
            def identityKeys=[] as Set
            def reviewed=[]
            def balances=[:]
            moves.each { m ->
                String key="smart-accountant:${company}:input_move:${m.id}"
                assert identityKeys.add(key): 'Duplicate source identity'
                def issues=[]
                BigDecimal d=null,v=null
                try { d=amount(m.dain); v=amount(m.madin) }
                catch(IllegalArgumentException e) { issues << 'INVALID_AMOUNT' }
                String date=null
                try { date=LocalDate.of(m.year as int,m.month as int,m.day as int).toString() }
                catch(Exception e) { issues << 'INVALID_DATE' }
                if(!accountsById.containsKey(m.per_id)) issues << 'UNKNOWN_ACCOUNT'
                if(!currencyIds.contains(m.cur)) issues << 'UNKNOWN_CURRENCY'
                if(m.cboxid != 0 && !cashboxIds.contains(m.cboxid)) issues << 'UNKNOWN_CASHBOX'
                if(d != null && v != null && (d<0 || v<0)) issues << 'NEGATIVE_AMOUNT'
                String det=norm(m.details)
                String kind='INVOICE_SOURCE'
                if(m.bill_type == 1) {
                    if(det.contains('رصيد سابق') || det.contains('افتتاح')) kind='OPENING_LABEL'
                    else if(det.startsWith('صرف عملات')) kind='CURRENCY_EXCHANGE_LABEL'
                    else if(det.startsWith('تحويل الى حساب') || det.startsWith('تحويل من حساب')) kind='ACCOUNT_TRANSFER_LABEL'
                    else if(d == 0 && v == 0) kind='ZERO_AMOUNT'
                    else if(m.cboxid != 0 && d != null && v != null && ((d>0 && v==0)||(v>0 && d==0))) kind='CASH_LINKED_SINGLE_SIDE'
                    else if(m.cboxid == 0) kind='NO_CASHBOX_REVIEW'
                    else kind='VOUCHER_REVIEW'
                }
                if(kind != 'INVOICE_SOURCE' && kind != 'CASH_LINKED_SINGLE_SIDE') issues << kind
                def rec=[sourceKey:key,sourceId:m.id,sourceRowSha256:hashText(JsonOutput.toJson(m)),
                    sourceAccountId:m.per_id,sourceGroupId:accountsById[m.per_id]?.type,
                    date:date,sourceCurrencyId:m.cur,sourceCashboxId:m.cboxid,
                    sourceBillType:m.bill_type,sourceState:m.bstate,
                    rawDain:m.dain,rawMadin:m.madin,sourceStructure:kind,
                    rawSide:d == null || v == null ? 'INVALID' :
                        d>0 && v==0 ? 'DAIN_ONLY' : v>0 && d==0 ? 'MADIN_ONLY' : d==0 && v==0 ? 'ZERO' : 'BOTH_OR_NEGATIVE',
                    issues:issues,approvedForImport:false]
                reviewed << rec
                if(d != null && v != null) {
                    String bk="${m.per_id}:${m.cur}"
                    def b=balances[bk]
                    if(b == null) {
                        b=[sourceAccountId:m.per_id,sourceCurrencyId:m.cur,
                           pre2026Dain:0G,pre2026Madin:0G,from2026Dain:0G,from2026Madin:0G,
                           allDain:0G,allMadin:0G,rows:0]
                        balances[bk]=b
                    }
                    b.allDain+=d; b.allMadin+=v; b.rows++
                    if(date != null) {
                        if(date < '2026-01-01') {b.pre2026Dain+=d;b.pre2026Madin+=v}
                        else {b.from2026Dain+=d;b.from2026Madin+=v}
                    }
                }
            }
            // Exact source labels and reciprocal account names identify candidate pairs.
            // Differences remain explicit; no balancing entry or cash movement is invented.
            def noCash=moves.findAll { it.bill_type==1 && it.cboxid==0 }
            def pairs=[]; def seen=[] as Set
            noCash.findAll { norm(it.details).startsWith('تحويل من حساب') }.each { right ->
                String fromName=norm(right.details).substring('تحويل من حساب'.length()).split(/\s+-\s*/,2)[0].trim()
                def candidates=noCash.findAll { left ->
                    left.id != right.id && norm(accountsById[left.per_id]?.name)==fromName &&
                    (!norm(left.details).startsWith('تحويل الى حساب') ||
                        norm(left.details).substring('تحويل الى حساب'.length()).split(/\s+-\s*/,2)[0].trim()==norm(accountsById[right.per_id]?.name)) &&
                    left.cur==right.cur &&
                    amount(left.dain)>0 && amount(left.madin)==0 && amount(right.madin)>0 && amount(right.dain)==0
                }
                // Adjacency is source evidence only, never permission to merge or post.
                def adjacent=candidates.findAll { Math.abs((it.id as long)-(right.id as long))==1L }
                if(adjacent.size()==1) candidates=adjacent
                if(candidates.size()==1) {
                    def left=candidates[0]; String pairKey=[left.id,right.id].sort().join(':')
                    if(seen.add(pairKey)) pairs << [sourceIds:[left.id,right.id],sourceCurrencyId:left.cur,
                        dainAmount:left.dain,madinAmount:right.madin,
                        pairingBasis:adjacent.size()==1?'ADJACENT_IDS_AND_EXPLICIT_SOURCE_ACCOUNT_NAME':'UNIQUE_EXPLICIT_SOURCE_ACCOUNT_NAME',
                        dateMatches:left.year==right.year && left.month==right.month && left.day==right.day,
                        sourceDates:[[left.year,left.month,left.day],[right.year,right.month,right.day]],
                        difference:amount(left.dain)-amount(right.madin),
                        status:amount(left.dain)==amount(right.madin)?'BALANCED_SOURCE_PAIR_CANDIDATE':'UNBALANCED_SOURCE_PAIR',
                        approvedForImport:false]
                } else {
                    pairs << [sourceIds:[right.id],status:'AMBIGUOUS_OR_MISSING_TRANSFER_PARTNER',
                        candidateSourceIds:candidates*.id,approvedForImport:false]
                }
            }
            balances.values().each { b ->
                b.rawNetDainMinusMadin=b.allDain-b.allMadin
                b.pre2026RawNet=b.pre2026Dain-b.pre2026Madin
            }
            assert digest(source)==sha: 'Source changed during review'
            [format:'nasqo-smart-accountant-reconciliation-v2',companyPartyId:company,sourceSha256:sha,
             sourceSizeBytes:source.length(),status:'REVIEW_ONLY_NOT_IMPORTED',
             financialDocumentsCreated:0,postedEntries:0,readyForAccountingImport:false,
             interpretation:[boxes:'account groups linked by customers.type',cboxes:'cashboxes',
                customers:'source account records, not automatically NASQO customers',
                macc:'measurement accounts, not a GL chart',
                rawAmounts:'Source dain/madin preserved; no GL debit/credit approval',
                balances:'Source-side arithmetic only; not a trial balance or new opening entries',
                identity:'Company + source system + table + source ID; changed row fingerprint requires review'],
             summary:[movements:moves.size(),accountGroups:groups.size(),sourceAccounts:accounts.size(),
                cashboxes:cashboxes.size(),sourceStructureCounts:reviewed.countBy{it.sourceStructure},
                rawCashVoucherSides:reviewed.findAll{it.sourceStructure=='CASH_LINKED_SINGLE_SIDE'}.countBy{it.rawSide},
                unknownCurrencyRows:reviewed.count{it.issues.contains('UNKNOWN_CURRENCY')},
                invalidAmountRows:reviewed.count{it.issues.contains('INVALID_AMOUNT')},
                invalidDateRows:reviewed.count{it.issues.contains('INVALID_DATE')},
                unknownAccountRows:reviewed.count{it.issues.contains('UNKNOWN_ACCOUNT')},
                unknownGroupAccounts:accounts.count{!groupIds.contains(it.type)},
                transferPairs:pairs.size(),unbalancedTransferPairs:pairs.count{it.status=='UNBALANCED_SOURCE_PAIR'},
                transferDateMismatches:pairs.count{it.dateMatches==false},
                ambiguousTransferPartners:pairs.count{it.status=='AMBIGUOUS_OR_MISSING_TRANSFER_PARTNER'},
                openingLabelRows:reviewed.count{it.sourceStructure=='OPENING_LABEL'}],
             accountGroups:groups,cashboxes:cashboxes,currencies:currencies,
             accounts:accounts.collect{it + [sourceKey:"smart-accountant:${company}:customers:${it.id}",targetGlAccountId:null,mappingApproved:false]},
             currencyExceptions:reviewed.findAll{it.issues.contains('UNKNOWN_CURRENCY')},
             openingCandidates:reviewed.findAll{it.sourceStructure=='OPENING_LABEL'},
             transferPairCandidates:pairs,sourceAccountBalances:balances.values().toList(),
             movements:reviewed]
        } finally { c.close() }
    }
}
return NasqoSmartAccountantReconciliation
