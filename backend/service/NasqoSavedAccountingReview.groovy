import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Read-only, company-scoped projection. Never exposes source paths or full account rows. */
class NasqoSavedAccountingReviewHelper {
    private static Map parsePrivate(File file, long maximum) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length()>maximum) {
            throw new IllegalStateException('Saved accounting review is unavailable')
        }
        def data=new JsonSlurper().parse(file,'UTF-8')
        if (!(data instanceof Map)) throw new IllegalStateException('Invalid saved review')
        data as Map
    }
    private static String sha256(File source) {
        def digest=MessageDigest.getInstance('SHA-256')
        source.withInputStream { input ->
            byte[] buf=new byte[65536]; int length
            while((length=input.read(buf)) != -1) digest.update(buf,0,length)
        }
        digest.digest().encodeHex().toString()
    }
    static Map read(File root, String company) {
        if(company==null || !(company ==~ /[A-Za-z0-9_-]{1,64}/))
            throw new IllegalArgumentException('Invalid company')
        Map empty=[available:false,status:'NO_SAVED_REVIEW',readOnly:true]
        if(!root.exists()) return empty
        if(Files.isSymbolicLink(root.toPath()) || !root.isDirectory())
            throw new IllegalStateException('Private review storage is unavailable')
        File folder=new File(root,company)
        if(Files.isSymbolicLink(folder.toPath())) throw new IllegalStateException('Unsafe review storage')
        if(!folder.exists()) return empty
        if(!folder.isDirectory()) throw new IllegalStateException('Invalid review storage')
        def sources=folder.listFiles()?.findAll { it.name ==~ /[a-f0-9]{64}/ }
        if(sources==null) throw new IllegalStateException('Review storage cannot be read')
        if(sources.isEmpty()) return empty
        if(sources.size()!=1) throw new IllegalStateException('Multiple sources require reconciliation')
        File stage=sources[0]
        if(Files.isSymbolicLink(stage.toPath()) || !stage.isDirectory())
            throw new IllegalStateException('Unsafe source storage')
        File source=new File(stage,'source.back')
        if(Files.isSymbolicLink(source.toPath()) || !source.isFile() || source.length()>32L*1024*1024)
            throw new IllegalStateException('Source verification failed')
        Map manifest=parsePrivate(new File(stage,'manifest.json'),1024L*1024)
        if(manifest.targetCompanyPartyId!=company || manifest.sourceSha256!=stage.name ||
                sha256(source)!=stage.name)
            throw new IllegalStateException('Company or source verification failed')
        File reportFile=new File(stage,'reconciliation-v2.json')
        if(!reportFile.exists()) return [available:false,status:'RECONCILIATION_PENDING',readOnly:true]
        Map report=parsePrivate(reportFile,24L*1024*1024)
        if(report.companyPartyId!=company || report.sourceSha256!=stage.name ||
                report.format!='nasqo-smart-accountant-reconciliation-v2' ||
                report.status!='REVIEW_ONLY_NOT_IMPORTED' ||
                report.financialDocumentsCreated!=0 || report.postedEntries!=0 ||
                report.readyForAccountingImport!=false)
            throw new IllegalStateException('Saved reconciliation verification failed')
        def pick={Map row,List keys -> row.subMap(keys)}
        List allowedSummary=['movements','accountGroups','sourceAccounts','cashboxes',
            'unknownCurrencyRows','openingLabelRows','unbalancedTransferPairs',
            'ambiguousTransferPartners','transferDateMismatches','sourceStructureCounts']
        [available:true,status:'REVIEW_ONLY_NOT_IMPORTED',readOnly:true,
         companyPartyId:company,sourceReference:stage.name.substring(0,16),
         summary:pick(report.summary as Map,allowedSummary),
         invoiceLines:manifest.sourceTableCounts?.bill_details ?: 0,
         groups:(report.accountGroups ?: []).take(500).collect {pick(it as Map,['id','name'])},
         cashboxes:(report.cashboxes ?: []).take(100).collect {pick(it as Map,['id','name','cur'])},
         currencies:(report.currencies ?: []).take(100).collect {pick(it as Map,['id','name'])},
         currencyExceptions:(report.currencyExceptions ?: []).take(500).collect {
             pick(it as Map,['sourceId','sourceCurrencyId','date','rawDain','rawMadin'])},
         transferPairs:(report.transferPairCandidates ?: []).take(500).collect {
             pick(it as Map,['sourceIds','sourceCurrencyId','dainAmount','madinAmount','difference','status','dateMatches'])},
         openingCandidates:(report.openingCandidates ?: []).take(500).collect {
             pick(it as Map,['sourceId','sourceCurrencyId','date','rawDain','rawMadin'])},
         financialDocumentsCreated:0,postedEntries:0,readyForAccountingImport:false]
    }
}
return NasqoSavedAccountingReviewHelper
