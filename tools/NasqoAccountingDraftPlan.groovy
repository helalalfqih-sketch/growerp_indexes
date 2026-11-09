import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

/** Prepares review drafts only. No Moqui entities, payments, invoices or ledger calls. */
class NasqoAccountingDraftPlanHelper {
    static String hash(File f) {
        def md=MessageDigest.getInstance('SHA-256')
        f.withInputStream { s -> byte[] b=new byte[65536]; int n
            while((n=s.read(b)) != -1) md.update(b,0,n)
        }
        md.digest().encodeHex().toString()
    }
    static BigDecimal decimal(Object v) {
        if(v == null || !(v.toString() ==~ /[+-]?\d+(\.\d+)?/))
            throw new IllegalArgumentException('Invalid source amount')
        new BigDecimal(v.toString())
    }
    static Map prepare(Map report, Map policy, Map evidence, List primary, List supplementary) {
        if(report.format!='nasqo-smart-accountant-reconciliation-v2' ||
           report.status!='REVIEW_ONLY_NOT_IMPORTED' || report.financialDocumentsCreated!=0 ||
           report.postedEntries!=0 || report.readyForAccountingImport!=false)
            throw new IllegalStateException('Source report is not review-only')
        if(policy.format!='nasqo-accounting-decisions-v1' ||
           policy.companyPartyId!=report.companyPartyId || policy.sourceSha256!=report.sourceSha256 ||
           policy.financialPostingAllowed!=false || policy.approval?.authority!='OWNER_INSTRUCTION')
            throw new IllegalStateException('Decision scope mismatch')
        def cp=policy.currencyPolicy
        if(cp?.mode!='SINGLE_CURRENCY_AS_DECLARED' || cp.targetCurrencyUomId!='YER' ||
           cp.amountTreatment!='PRESERVE_NUMERIC_VALUES' || cp.fxConversion!=false ||
           cp.sourceCurrencyIds!=[1,2,3])
            throw new IllegalStateException('Currency decision mismatch')
        if(evidence.source?.sha256!=report.sourceSha256 ||
           evidence.target?.companyPartyId!=report.companyPartyId ||
           evidence.report?.matchingVoucherIds!=evidence.report?.rows ||
           evidence.report?.matchingBothRawAmountColumns!=evidence.report?.rows)
            throw new IllegalStateException('Document-label evidence mismatch')
        def types=[1:['VOUCHER_SUBTYPE_PENDING','سند'],2:['SALES_INVOICE','فاتورة بيع'],
            3:['PURCHASE_INVOICE','فاتورة شراء'],4:['SALES_RETURN','مردود بيع'],
            5:['PURCHASE_RETURN','مردود شراء'],7:['FREEFORM_SALES_INVOICE','بيع حرة'],
            9:['FREEFORM_PURCHASE_INVOICE','شراء حرة']]
        def proof=evidence.billTypeEvidence.collectEntries{[(it.code as int):it]}
        def usedLineIds=[] as Set
        def lines=[:].withDefault{[]}
        [[name:'bill_details',rows:primary],[name:'bill_details2',rows:supplementary]].each { source ->
            source.rows.each { row ->
                String key="smart-accountant:${report.companyPartyId}:${source.name}:${row.id}"
                if(!usedLineIds.add(key)) throw new IllegalStateException('Duplicate source line')
                ['price','amount','total'].each { decimal(row[it]) }
                lines[row.bill_id] << [sourceTable:source.name,sourceId:row.id,sourceKey:key,
                    sourceProductId:row.prod_id,sourceStoreId:row.store_id,description:row.details,
                    quantity:row.amount,unitPrice:row.price,lineTotal:row.total,
                    sourceUnit:row.unit,sourceUnitQuantity:row.unitnum,
                    sourceDiscount:row.discount,sourceBonus:row.bonus,
                    currencyUomId:'YER']
            }
        }
        def ids=[] as Set;def keys=[] as Set
        def exceptional=report.transferPairCandidates.findAll{it.status!='BALANCED_SOURCE_PAIR_CANDIDATE'}
            .collectMany{it.sourceIds} as Set
        def drafts=report.movements.collect { row ->
            if(!ids.add(row.sourceId) || !keys.add(row.sourceKey))
                throw new IllegalStateException('Duplicate source movement')
            if(row.sourceKey!="smart-accountant:${report.companyPartyId}:input_move:${row.sourceId}" ||
               !(row.sourceRowSha256 ==~ /[a-f0-9]{64}/))
                throw new IllegalStateException('Invalid source identity')
            if(!cp.sourceCurrencyIds.contains(row.sourceCurrencyId))
                throw new IllegalStateException('Unapproved source currency')
            decimal(row.rawDain);decimal(row.rawMadin)
            def type=types[row.sourceBillType as int]
            def observed=proof[row.sourceBillType as int]
            if(!type || observed?.sourceLabel!=type[1] || !(observed.matchedReportRows>0))
                throw new IllegalStateException('Unproven document type')
            def holds=['ACCOUNT_MAPPING_PENDING']
            if(row.sourceBillType==1) holds << 'VOUCHER_DIRECTION_PENDING'
            if(row.sourceStructure=='OPENING_LABEL') holds << 'OPENING_BALANCE_REVIEW'
            if(exceptional.contains(row.sourceId)) holds << 'TRANSFER_REVIEW'
            if(row.sourceStructure=='CURRENCY_EXCHANGE_LABEL') holds << 'SOURCE_EXCHANGE_REVIEW'
            holds.addAll((row.issues ?: []).findAll{it!='UNKNOWN_CURRENCY'})
            [draftKey:row.sourceKey,sourceId:row.sourceId,sourceRowSha256:row.sourceRowSha256,
             date:row.date,sourceAccountId:row.sourceAccountId,sourceGroupId:row.sourceGroupId,
             sourceCashboxId:row.sourceCashboxId,sourceBillType:row.sourceBillType,
             sourceCurrencyId:row.sourceCurrencyId,currencyUomId:'YER',
             rawDain:row.rawDain,rawMadin:row.rawMadin,documentType:type[0],sourceLabel:type[1],
             sourceStructure:row.sourceStructure,lines:lines[row.sourceId],
             targetAccountId:null,targetDocumentId:null,
             status:'PREPARED_REQUIRES_MAPPING',holds:holds.unique(),
             readyForFinancialCreation:false]
        }
        if(lines.keySet().any{!ids.contains(it)}) throw new IllegalStateException('Orphan source lines')
        if(drafts.size()!=report.summary.movements) throw new IllegalStateException('Movement count mismatch')
        def typeCounts=drafts.groupBy{it.sourceBillType}.collect{k,v->
            [sourceBillType:k,sourceLabel:types[k as int][1],count:v.size()]}
            .sort{it.sourceBillType}
        [format:'nasqo-accounting-draft-plan-v1',companyPartyId:report.companyPartyId,
         sourceSha256:report.sourceSha256,status:'PREPARED_REQUIRES_MAPPING',
         currencyUomId:'YER',amountTreatment:'PRESERVE_NUMERIC_VALUES',fxConversion:false,
         summary:[preparedDrafts:drafts.size(),invoiceSourceDrafts:drafts.count{it.sourceBillType!=1},
            voucherSourceDrafts:drafts.count{it.sourceBillType==1},
            primaryLines:primary.size(),supplementaryLines:supplementary.size(),
            resolvedCurrencyRows:report.currencyExceptions.size(),unresolvedCurrencyRows:0,
            openingHeldRows:drafts.count{it.holds.contains('OPENING_BALANCE_REVIEW')},
            transferHeldRows:drafts.count{it.holds.contains('TRANSFER_REVIEW')},
            readyForFinancialCreation:0],
         documentTypes:typeCounts,
         accounts:report.accounts.collect{it + [targetAccountId:null,mappingApproved:false]},
         cashboxes:report.cashboxes.collect{it + [currencyUomId:'YER',targetAccountId:null,mappingApproved:false]},
         drafts:drafts,financialDocumentsCreated:0,postedEntries:0,
         readyForAccountingImport:false]
    }
}
return NasqoAccountingDraftPlanHelper
