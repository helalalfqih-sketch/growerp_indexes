import java.time.LocalDate

/** Only exact source invoice references establish a direction, never raw columns alone. */
class NasqoNativeVoucherPlanHelper {
 static BigDecimal money(Object v) {
  if(v==null || !(v.toString()==~/[+-]?\d+(\.\d+)?/))throw new IllegalArgumentException('Invalid amount')
  new BigDecimal(v.toString())
 }
 static Map prepare(Map plan,Map sourceHeaders,Map nativeInvoices) {
  if(plan.format!='nasqo-accounting-draft-plan-v1'||plan.currencyUomId!='YER'||plan.fxConversion!=false)
   throw new IllegalArgumentException('Invalid source plan')
  def drafts=plan.drafts.collectEntries{[(it.sourceId.toString()):it]}
  if(drafts.size()!=plan.drafts.size())throw new IllegalStateException('Duplicate source ID')
  def candidates=[];def held=[]
  def reject={d,reason->held<<[sourceId:d.sourceId.toString(),reason:reason]}
  drafts.values().findAll{it.sourceBillType==1}.each{d->
   def h=sourceHeaders[d.sourceId.toString()]
   def match=((h?.details?:'').toString().trim() =~ /^واصل من فاتورة رقم (\d+)$/)
   if(!match.matches()){reject(d,'EXPLICIT_INVOICE_REFERENCE_REQUIRED');return}
   String invoiceSourceId=match[0][1]
   def inv=drafts[invoiceSourceId];def nativeInv=nativeInvoices[invoiceSourceId]
   if(!inv || !nativeInv){reject(d,'REFERENCED_NATIVE_INVOICE_MISSING');return}
   if(!(inv.sourceBillType in [2,3,7,9])){reject(d,'RETURN_OR_UNVERIFIED_INVOICE_TYPE');return}
   if(d.sourceAccountId.toString()!=inv.sourceAccountId.toString() ||
      d.sourceCurrencyId.toString()!=inv.sourceCurrencyId.toString()){
    reject(d,'SOURCE_ACCOUNT_OR_CURRENCY_MISMATCH');return
   }
   if(d.sourceStructure!='CASH_LINKED_SINGLE_SIDE' || !plan.cashboxes.any{it.id.toString()==d.sourceCashboxId.toString()} ||
      (d.holds?:[]).any{!(it in ['ACCOUNT_MAPPING_PENDING','VOUCHER_DIRECTION_PENDING'])}){
    reject(d,'SOURCE_STRUCTURE_REVIEW');return
   }
   boolean incoming=inv.sourceBillType in [2,7]
   def amount=money(incoming?d.rawMadin:d.rawDain)
   def opposite=money(incoming?d.rawDain:d.rawMadin)
   if(amount<=0 || opposite!=0 || money(incoming?inv.rawMadin:inv.rawDain)!=0 ||
      money(incoming?inv.rawDain:inv.rawMadin)!=money(nativeInv.invoiceTotal)){
    reject(d,'SOURCE_SIDE_OR_INVOICE_TOTAL_REVIEW');return
   }
   if(LocalDate.parse(d.date.toString()).isBefore(LocalDate.parse(inv.date.toString()))){
    reject(d,'PAYMENT_PREDATES_INVOICE');return
   }
   if(['discount','discount_amount','bonus','added','added2'].any{money(h[it]?:'0')!=0}){
    reject(d,'SOURCE_ADJUSTMENT_REVIEW');return
   }
   candidates<<[sourceId:d.sourceId.toString(),sourceRowSha256:d.sourceRowSha256,
    sourceAccountId:d.sourceAccountId.toString(),sourceCashboxId:d.sourceCashboxId.toString(),
    sourceCurrencyId:d.sourceCurrencyId.toString(),sourceInvoiceId:invoiceSourceId,
    invoiceId:nativeInv.invoiceId,fromPartyId:nativeInv.toPartyId,toPartyId:nativeInv.fromPartyId,
    incoming:incoming,amount:amount,date:d.date,rawDain:money(d.rawDain),rawMadin:money(d.rawMadin),
    reference:'SAP-'+plan.companyPartyId+'-V-'+d.sourceId,
    sourceNote:h.details.toString(),invoiceTotal:money(nativeInv.invoiceTotal)]
  }
  def out=[]
  candidates.groupBy{it.sourceInvoiceId}.each{k,v->
   if(v.sum{it.amount}>v[0].invoiceTotal)v.each{reject(it,'LINKED_PAYMENTS_EXCEED_INVOICE')}
   else out.addAll(v)
  }
  [payments:out,held:held,summary:[eligiblePayments:out.size(),heldVouchers:held.size(),
   incoming:out.count{it.incoming},outgoing:out.count{!it.incoming},
   currencyUomId:'YER',amount:out.sum{it.amount}?:0G]]
 }
}
return NasqoNativeVoucherPlanHelper
