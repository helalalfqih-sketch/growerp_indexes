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
        Map currencyDecision=null
        Map preparedImport=null
        File decisionFile=new File(stage,'import-decisions-v1.json')
        if(Files.exists(decisionFile.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            Map decision=parsePrivate(decisionFile,1024L*1024)
            Map cp=decision.currencyPolicy as Map
            if(decision.format!='nasqo-accounting-decisions-v1' ||
                    decision.companyPartyId!=company || decision.sourceSha256!=stage.name ||
                    decision.financialPostingAllowed!=false ||
                    decision.approval?.authority!='OWNER_INSTRUCTION' ||
                    cp?.mode!='SINGLE_CURRENCY_AS_DECLARED' || cp.targetCurrencyUomId!='YER' ||
                    cp.sourceCurrencyIds!=[1,2,3] || cp.amountTreatment!='PRESERVE_NUMERIC_VALUES' ||
                    cp.fxConversion!=false)
                throw new IllegalStateException('Currency decision verification failed')
            currencyDecision=[targetCurrencyUomId:'YER',sourceCurrencyIds:[1,2,3],
                amountTreatment:'PRESERVE_NUMERIC_VALUES',fxConversion:false,
                resolvedSourceCurrencyRows:(report.currencyExceptions ?: []).count{
                    cp.sourceCurrencyIds.contains(it.sourceCurrencyId)}]
            File planManifestFile=new File(stage,'draft-plan-manifest-v1.json')
            if(Files.exists(planManifestFile.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                Map pm=parsePrivate(planManifestFile,1024L*1024)
                File planFile=new File(stage,'draft-plan-v1.json')
                if(pm.format!='nasqo-accounting-draft-manifest-v1' ||
                        pm.companyPartyId!=company || pm.sourceSha256!=stage.name ||
                        pm.decisionSha256!=sha256(decisionFile) ||
                        pm.status!='PREPARED_REQUIRES_MAPPING' || pm.currencyUomId!='YER' ||
                        pm.financialDocumentsCreated!=0 || pm.postedEntries!=0 ||
                        pm.readyForAccountingImport!=false ||
                        pm.summary?.readyForFinancialCreation!=0 ||
                        pm.summary?.preparedDrafts!=report.summary.movements ||
                        Files.isSymbolicLink(planFile.toPath()) || !planFile.isFile() ||
                        planFile.length()>64L*1024*1024 || pm.planSha256!=sha256(planFile))
                    throw new IllegalStateException('Prepared draft verification failed')
                preparedImport=[status:pm.status,currencyUomId:'YER',
                    summary:(pm.summary as Map).subMap(['preparedDrafts','invoiceSourceDrafts',
                        'voucherSourceDrafts','primaryLines','supplementaryLines',
                        'resolvedCurrencyRows','unresolvedCurrencyRows','openingHeldRows',
                        'transferHeldRows','readyForFinancialCreation']),
                    documentTypes:(pm.documentTypes ?: []).take(20).collect{
                        (it as Map).subMap(['sourceBillType','sourceLabel','count'])}]
            }
        }
        def pick={Map row,List keys -> row.subMap(keys)}
        List allowedSummary=['movements','accountGroups','sourceAccounts','cashboxes',
            'unknownCurrencyRows','openingLabelRows','unbalancedTransferPairs',
            'ambiguousTransferPartners','transferDateMismatches','sourceStructureCounts']
        Map summary=pick(report.summary as Map,allowedSummary)
        if(currencyDecision) {
            summary.sourceUnknownCurrencyRows=summary.unknownCurrencyRows ?: 0
            summary.unknownCurrencyRows=(report.currencyExceptions ?: []).size()-currencyDecision.resolvedSourceCurrencyRows
        }
        [available:true,status:'REVIEW_ONLY_NOT_IMPORTED',readOnly:true,
         companyPartyId:company,sourceReference:stage.name.substring(0,16),
         summary:summary,currencyDecision:currencyDecision,preparedImport:preparedImport,
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
