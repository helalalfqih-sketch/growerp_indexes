import groovy.json.*
def h=new GroovyShell(this.class.classLoader).evaluate(new File(args[0]))
def sha='a'*64
def row={int id,int kind,int currency,String d,String m,String structure ->
 [sourceKey:"smart-accountant:TEST:input_move:${id}",sourceId:id,sourceRowSha256:'b'*64,
 date:'2026-01-01',sourceAccountId:1,sourceGroupId:1,sourceCashboxId:0,
 sourceBillType:kind,sourceCurrencyId:currency,rawDain:d,rawMadin:m,
 sourceStructure:structure,issues:(currency == 3 ? ['UNKNOWN_CURRENCY'] : [])]
}
def movements=[row(1,3,3,'0','20000','INVOICE_SOURCE'),
 row(147,1,1,'0','20200','OPENING_LABEL'),row(1498,1,1,'246000','0','ACCOUNT_TRANSFER_LABEL'),
 row(1499,1,1,'0','239500','ACCOUNT_TRANSFER_LABEL')]
def report=[format:'nasqo-smart-accountant-reconciliation-v2',status:'REVIEW_ONLY_NOT_IMPORTED',
 companyPartyId:'TEST',sourceSha256:sha,financialDocumentsCreated:0,postedEntries:0,
 readyForAccountingImport:false,summary:[movements:4],accounts:[[id:1,name:'synthetic account']],
 cashboxes:[[id:1,name:'synthetic cash']],movements:movements,currencyExceptions:[movements[0]],
 transferPairCandidates:[[sourceIds:[1498,1499],status:'UNBALANCED_SOURCE_PAIR']]]
def policy=[format:'nasqo-accounting-decisions-v1',companyPartyId:'TEST',sourceSha256:sha,
 financialPostingAllowed:false,approval:[authority:'OWNER_INSTRUCTION'],currencyPolicy:[
 mode:'SINGLE_CURRENCY_AS_DECLARED',targetCurrencyUomId:'YER',sourceCurrencyIds:[1,2,3],
 amountTreatment:'PRESERVE_NUMERIC_VALUES',fxConversion:false]]
def evidence=[source:[sha256:sha],target:[companyPartyId:'TEST'],
 report:[rows:2,matchingVoucherIds:2,matchingBothRawAmountColumns:2],
 billTypeEvidence:[[code:1,sourceLabel:'سند',matchedReportRows:1],[code:3,sourceLabel:'فاتورة شراء',matchedReportRows:1]]]
def lines=[[id:1,bill_id:1,price:'4000',amount:5,total:'20000',discount:'0',bonus:'0']]
def plan=h.prepare(report,policy,evidence,lines,[])
assert plan.summary.preparedDrafts==4 && plan.summary.invoiceSourceDrafts==1
assert plan.summary.resolvedCurrencyRows==1 && plan.summary.unresolvedCurrencyRows==0
assert plan.summary.openingHeldRows==1 && plan.summary.transferHeldRows==2
assert plan.drafts.every{it.currencyUomId=='YER' && !it.readyForFinancialCreation && it.targetDocumentId==null}
assert plan.drafts[0].sourceCurrencyId==3 && plan.drafts[0].rawMadin=='20000'
assert plan.drafts[0].lines[0].lineTotal=='20000'
assert plan.financialDocumentsCreated==0 && plan.postedEntries==0 && !plan.readyForAccountingImport
assert JsonOutput.toJson(plan)==JsonOutput.toJson(h.prepare(report,policy,evidence,lines,[]))
def copy={x->new JsonSlurper().parseText(JsonOutput.toJson(x))}
def reject={Closure call -> try{call();assert false} catch(IllegalStateException expected){}}
def wrong=copy(policy);wrong.companyPartyId='OTHER';reject{h.prepare(report,wrong,evidence,lines,[])}
wrong=copy(policy);wrong.currencyPolicy.fxConversion=true;reject{h.prepare(report,wrong,evidence,lines,[])}
def changed=copy(report);changed.movements[0].sourceCurrencyId=9;reject{h.prepare(changed,policy,evidence,lines,[])}
changed=copy(report);changed.movements<<changed.movements[0];reject{h.prepare(changed,policy,evidence,lines,[])}
def proof=copy(evidence);proof.billTypeEvidence[1].sourceLabel='unproven';reject{h.prepare(report,policy,proof,lines,[])}
reject{h.prepare(report,policy,evidence,lines+[lines[0]],[])}
def orphan=copy(lines);orphan[0].bill_id=999;reject{h.prepare(report,policy,evidence,orphan,[])}
println 'PASS_DRAFT_POLICY_SCOPE_AMOUNT_PRESERVATION_IDENTITY_EVIDENCE_AND_HOLDS'
