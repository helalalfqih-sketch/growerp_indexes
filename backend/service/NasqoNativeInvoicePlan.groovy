import groovy.json.JsonOutput
import java.security.MessageDigest
import java.time.LocalDate

/** Converts verified source invoices into native draft inputs. Never posts or settles them. */
class NasqoNativeInvoicePlanHelper {
 static String digest(String value) { MessageDigest.getInstance('SHA-256').digest(value.getBytes('UTF-8')).encodeHex().toString() }
 static BigDecimal money(Object v) {
  if(v==null || !(v.toString()==~/[+-]?\d+(\.\d+)?/)) throw new IllegalArgumentException('Invalid source amount')
  new BigDecimal(v.toString())
 }
 static Map prepare(Map plan,Map approval,Map products,Map headers) {
  if(plan.format!='nasqo-accounting-draft-plan-v1'||plan.currencyUomId!='YER'||plan.fxConversion!=false||
     approval.format!='nasqo-native-draft-approval-v1'||approval.companyPartyId!=plan.companyPartyId||
     approval.sourceSha256!=plan.sourceSha256||approval.postingAllowed!=false||
     approval.authority!='OWNER_INSTRUCTION'||!approval.instruction)
    throw new IllegalStateException('Native import scope/approval mismatch')
  def accounts=plan.accounts.collectEntries{[(it.id.toString()):it]}
  def selected=approval.counterparties.collectEntries{[(it.sourceAccountId.toString()):it]}
  selected.each{k,v->if(!accounts[k] || v.sourceNameSha256!=digest(accounts[k].name.toString()))throw new IllegalStateException('Counterparty changed')}
  def ids=[] as Set;def out=[];def held=[]
  plan.drafts.each { d ->
   String id=d.sourceId.toString()
   if(!ids.add(id)||d.draftKey!="smart-accountant:${plan.companyPartyId}:input_move:${id}"||
      !(d.sourceRowSha256==~/[0-9a-f]{64}/))throw new IllegalStateException('Invalid source identity')
   def reasons=[]
   int type=d.sourceBillType as int
   if(!(type in [2,3,4,5,7,9])) reasons << 'VOUCHER_DIRECTION_PENDING'
   def account=accounts[d.sourceAccountId.toString()]
   if(!selected[d.sourceAccountId.toString()])reasons << 'COUNTERPARTY_MAPPING_PENDING'
   def lines=[]
   (d.lines?:[]).each { l ->
    def qty=money(l.quantity);def price=money(l.unitPrice);def total=money(l.lineTotal)
    if(qty<=0||price<0||qty*price!=total||money(l.sourceDiscount?:'0')!=0||money(l.sourceBonus?:'0')!=0)
     reasons << 'LINE_AMOUNT_REVIEW'
    String name=products[l.sourceProductId.toString()]?:l.description?.toString()?.trim()
    if(!name)reasons << 'LINE_DESCRIPTION_REVIEW'
    lines << [sourceKey:l.sourceKey,quantity:qty,amount:price,lineTotal:total,
      itemTypeEnumId:'ItemProduct',otherPartyProductId:l.sourceProductId?.toString(),
      description:([name,l.description?.toString()?.trim(),l.sourceUnit?.toString()?.trim()].findAll{it}.unique().join(' · '))]
   }
   def total=lines.sum{it.lineTotal}?:0G
   def expected=money(([2,5,7].contains(type)?d.rawDain:d.rawMadin))
   if(type!=1 && (total<=0 || total!=expected))reasons << 'HEADER_TOTAL_REVIEW'
   def header=headers[id]
   if(!header)throw new IllegalStateException('Source header missing')
   ['discount','discount_amount','bonus','added','added2'].each{field->
    if(money(header[field]?:'0')!=0)reasons << 'HEADER_ADJUSTMENT_REVIEW'
   }
   LocalDate.parse(d.date.toString())
   reasons.addAll((d.holds?:[]).findAll{!(it in ['ACCOUNT_MAPPING_PENDING','VOUCHER_DIRECTION_PENDING'])})
   if(reasons){held<<[sourceId:id,reasons:reasons.unique()];return}
   boolean outgoing=type in [2,5,7]
   out << [sourceId:id,sourceAccountId:d.sourceAccountId.toString(),sourceRowSha256:d.sourceRowSha256,
    sourceBillType:type,sourceCurrencyId:d.sourceCurrencyId,sourceCashboxId:d.sourceCashboxId,
    rawDain:money(d.rawDain),rawMadin:money(d.rawMadin),date:d.date.toString(),
    currencyUomId:'YER',invoiceTypeEnumId:type in [4,5]?'InvoiceReturn':'InvoiceSales',
    fromCompany:outgoing,statusId:outgoing?'InvoiceInProcess':'InvoiceIncoming',
    counterpartyRole:type in [2,4,7]?'Customer':'Vendor',
    reference:"SAP-${plan.companyPartyId}-${id}",total:total,items:lines,
    description:"${d.sourceLabel} — المحاسب الذكي #${id} — مسودة للمراجعة",
    sourceNote:header.details?.toString()?:'']
  }
  [companyPartyId:plan.companyPartyId,sourceSha256:plan.sourceSha256,invoices:out,held:held,
   counterparties:accounts.findAll{k,v->out.any{it.sourceAccountId==k}},
   summary:[eligibleInvoices:out.size(),heldMovements:held.size(),
    sourceMovements:plan.drafts.size(),invoiceItems:out.sum{it.items.size()}?:0,
    counterparties:out.collect{it.sourceAccountId}.unique().size(),
    documentTypes:out.countBy{it.sourceBillType},postedEntries:0]]
 }
}
return NasqoNativeInvoicePlanHelper
