import groovy.json.*
import java.sql.DriverManager
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

class NasqoNativeVoucherImportHelper {
 static Map run(def ec,String company,String owner,String sha,String mode) {
  if(!(mode in ['check','rollback','apply','verify'])||!(sha==~/[a-f0-9]{64}/)||
     company!=System.getenv('NASQO_ACCOUNTING_STAGE_COMPANY')||!ec.user.isInGroup('GROWERP_M_ADMIN'))
   throw new IllegalStateException('Import scope/role rejected')
  def old=ec.resource.script('component://growerp/service/NasqoNativeImport.groovy',null)
  File base=new File('/var/lib/nasqo-accounting-imports/'+company+'/'+sha)
  for(File p=base;p.absolutePath!='/var/lib';p=p.parentFile)
   if(Files.isSymbolicLink(p.toPath()))throw new IllegalStateException('Unsafe private path')
  def read={n->def f=new File(base,n);if(!f.isFile()||Files.isSymbolicLink(f.toPath())||f.length()>67108864)throw new IllegalStateException('Unsafe source file');new JsonSlurper().parse(f)}
  def manifest=read('draft-plan-manifest-v1.json'),decision=read('import-decisions-v1.json')
  def instruction=read('voucher-draft-instruction-v1.json')
  String planHash=old.hash(new File(base,'draft-plan-v1.json'))
  if(old.hash(new File(base,'source.back'))!=sha||manifest.sourceSha256!=sha||manifest.companyPartyId!=company||
     manifest.planSha256!=planHash||manifest.decisionSha256!=old.hash(new File(base,'import-decisions-v1.json'))||
     decision.companyPartyId!=company||decision.sourceSha256!=sha||decision.financialPostingAllowed!=false||
     decision.currencyPolicy?.targetCurrencyUomId!='YER'||decision.currencyPolicy?.fxConversion!=false||
     decision.currencyPolicy?.amountTreatment!='PRESERVE_NUMERIC_VALUES'||
     instruction.companyPartyId!=company||instruction.sourceSha256!=sha||instruction.planSha256!=planHash||
     instruction.authority!='OWNER_INSTRUCTION'||!instruction.instruction||
     instruction.scope!='EXACT_INVOICE_LINKED_DRAFTS_ONLY'||instruction.postingAllowed!=false||
     instruction.paymentApplicationsAllowed!=false)throw new IllegalStateException('Source/instruction integrity failed')
  def plan=read('draft-plan-v1.json')
  if(plan.companyPartyId!=company||plan.sourceSha256!=sha)throw new IllegalStateException('Plan scope changed')
  def pref=ec.entity.find('mantle.ledger.config.PartyAcctgPreference').condition('organizationPartyId',company).one()
  if(pref?.baseCurrencyUomId!='YER'||ec.entity.find('mantle.party.Party').condition('partyId',company).one()?.ownerPartyId!=owner)
   throw new IllegalStateException('Company/currency changed')
  def headers=[:]
  Class.forName('org.sqlite.JDBC')
  def c=DriverManager.getConnection('jdbc:sqlite:file:'+new File(base,'source.back').absolutePath+'?mode=ro')
  try{
   c.createStatement().execute('PRAGMA query_only=ON')
   def rs=c.createStatement().executeQuery('SELECT * FROM input_move')
   while(rs.next()){def row=[:];(1..rs.metaData.columnCount).each{row[rs.metaData.getColumnName(it)]=rs.getObject(it)};headers[row.id.toString()]=row};rs.close()
  }finally{c.close()}
  def invoiceRegs=ec.entity.find('growerp.nasqo.AccountingImportDocument').condition('companyPartyId',company).list()
  def sourceDrafts=plan.drafts.collectEntries{[(it.sourceId.toString()):it]}
  def parties=ec.entity.find('growerp.nasqo.AccountingImportParty').condition('companyPartyId',company).list().collectEntries{[(it.sourceAccountId):it.partyId]}
  def invoices=[:]
  invoiceRegs.each{reg->
   def d=sourceDrafts[reg.sourceId]
   if(!d||reg.sourceSha256!=sha||reg.planSha256!=planHash||reg.sourceRowSha256!=d.sourceRowSha256)
    throw new IllegalStateException('Imported source identity changed')
   def inv=ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId',reg.invoiceId).one()
   boolean outgoing=d.sourceBillType in [2,5,7]
   if(!inv||!parties[d.sourceAccountId.toString()]||
      inv.fromPartyId!=(outgoing?company:parties[d.sourceAccountId.toString()])||
      inv.toPartyId!=(outgoing?parties[d.sourceAccountId.toString()]:company)||
      inv.externalId!='SAP-'+company+'-'+d.sourceId||inv.currencyUomId!='YER'||
      !(inv.statusId in ['InvoiceInProcess','InvoiceIncoming'])||
      inv.invoiceTypeEnumId!=(d.sourceBillType in [4,5]?'InvoiceReturn':'InvoiceSales'))
    throw new IllegalStateException('Native invoice changed')
   invoices[reg.sourceId]=[invoiceId:inv.invoiceId,fromPartyId:inv.fromPartyId,toPartyId:inv.toPartyId,invoiceTotal:inv.invoiceTotal]
  }
  def planner=ec.resource.script('component://growerp/service/NasqoNativeVoucherPlan.groovy',null)
  def prepared=planner.prepare(plan,headers,invoices)
  def result=[status:'CHECKED_NATIVE_VOUCHERS',companyPartyId:company,sourceSha256:sha,
   eligible:prepared.summary,heldReasonCounts:prepared.held.countBy{it.reason},created:0,existing:0,postedEntries:0,applicationsCreated:0]
  def plannedIds=prepared.payments*.sourceId as Set
  def existingRecords=ec.entity.find('growerp.nasqo.AccountingImportPayment').condition('companyPartyId',company).list()
  if(existingRecords.any{!plannedIds.contains(it.sourceId)})throw new IllegalStateException('Previously imported voucher no longer matches its source invoice')
  if(mode=='check')return result
  def registry='growerp.nasqo.AccountingImportPayment'
  def call={name,p->def r=ec.service.sync().name(name).parameters(p).call();if(ec.message.hasError())throw new IllegalStateException(ec.message.errorsString);r}
  def verify={d,rec->
   def p=ec.entity.find('mantle.account.payment.Payment').condition('paymentId',rec.paymentId).one()
   if(rec.sourceSha256!=sha||rec.planSha256!=planHash||rec.sourceRowSha256!=d.sourceRowSha256||
      rec.sourceInvoiceId!=d.sourceInvoiceId||rec.invoiceId!=d.invoiceId||!p||
      p.fromPartyId!=d.fromPartyId||p.toPartyId!=d.toPartyId||p.statusId!='PmntProposed'||
      p.forInvoiceId!=d.invoiceId||p.amount!=d.amount||p.amountUomId!='YER'||
      p.paymentTypeEnumId!='PtInvoicePayment'||p.paymentRefNum!=d.reference||
      p.effectiveDate!=java.sql.Timestamp.valueOf(d.date+' 00:00:00')||
      p.paymentGatewayConfigId||p.paymentMethodId||p.toPaymentMethodId||p.finAccountId||p.paymentInstrumentEnumId||
      ec.entity.find('mantle.account.payment.PaymentApplication').condition('paymentId',p.paymentId).count()!=0||
      ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('paymentId',p.paymentId).count()!=0)
    throw new IllegalStateException('Imported voucher changed: '+d.sourceId)
   p.paymentId
  }
  def createOne={d->
   if(ec.entity.find('growerp.nasqo.AccountingImportDocument').condition([companyPartyId:company,sourceId:d.sourceId]).one())
    throw new IllegalStateException('Source already registered as invoice')
   def rec=ec.entity.find(registry).condition([companyPartyId:company,sourceId:d.sourceId]).one()
   if(rec){verify(d,rec);result.existing++;return}
   if(mode=='verify')throw new IllegalStateException('Missing imported voucher')
   if(ec.entity.find('mantle.account.payment.Payment').condition('paymentRefNum',d.reference).count())
    throw new IllegalStateException('Unregistered duplicate reference')
   call('create#'+registry,[companyPartyId:company,sourceId:d.sourceId,sourceSha256:sha,
    sourceRowSha256:d.sourceRowSha256,planSha256:planHash,sourceAccountId:d.sourceAccountId,
    sourceCashboxId:d.sourceCashboxId,sourceCurrencyId:d.sourceCurrencyId,sourceInvoiceId:d.sourceInvoiceId,
    rawDain:d.rawDain,rawMadin:d.rawMadin,invoiceId:d.invoiceId,direction:d.incoming?'INCOMING':'OUTGOING',
    creationStatus:'DRAFT_NEEDS_REVIEW',financialPostingApproved:'N',createdDate:ec.user.nowTimestamp])
   // Entity-auto creation intentionally leaves the unknown instrument unset.
   // create#Payment's high-level default would incorrectly claim a company check.
   def p=call('create#mantle.account.payment.Payment',[fromPartyId:d.fromPartyId,toPartyId:d.toPartyId,
    pseudoId:'SAP'+company+'V'+d.sourceId,paymentRefNum:d.reference,forInvoiceId:d.invoiceId,
    paymentTypeEnumId:'PtInvoicePayment',statusId:'PmntProposed',amount:d.amount,amountUomId:'YER',
    appliedTotal:0G,unappliedTotal:d.amount,effectiveDate:java.sql.Timestamp.valueOf(d.date+' 00:00:00'),
    memo:(d.incoming?'سند قبض':'سند صرف')+' — المحاسب الذكي #'+d.sourceId,
    comments:'مسودة تاريخية للمراجعة؛ لا تنفذ دفعًا. '+d.sourceNote+' — صندوق المصدر #'+d.sourceCashboxId+
     '. اتجاه السند مستنتج من الفاتورة والحساب المتطابقين؛ لم يُعتمد الحساب المالي أو تُنفذ تسوية.'])
   call('update#'+registry,[companyPartyId:company,sourceId:d.sourceId,paymentId:p.paymentId])
   verify(d,ec.entity.find(registry).condition([companyPartyId:company,sourceId:d.sourceId]).one())
   result.created++
  }
  long initialLedger=ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('organizationPartyId',company).count()
  long initialApps=ec.entity.find('mantle.account.payment.PaymentApplication').count()
  if(mode=='rollback'){
   long initialPayments=ec.entity.find('mantle.account.payment.Payment').count()
   long initialRegs=ec.entity.find(registry).count()
   def selected=prepared.payments.groupBy{it.incoming}.values().collect{it[0]}
   boolean began=ec.transaction.begin(600)
   try{
    selected.each{createOne(it)};selected.each{createOne(it)}
    assert result.created==selected.size() && result.existing==selected.size()
    def d=selected[0];def rec=ec.entity.find(registry).condition([companyPartyId:company,sourceId:d.sourceId]).one()
    boolean blocked=false
    try{call('update#mantle.account.payment.Payment',[paymentId:rec.paymentId,statusId:'PmntPromised'])}
    catch(Exception e){blocked=(e.message+' '+ec.message.errorsString).contains('هذا سند مستورد')}
    assert blocked
    result.postingGuardVerified=true
   }finally{ec.transaction.rollback(began,'Voucher rollback verification',null);ec.message.clearErrors()}
   assert ec.entity.find('mantle.account.payment.Payment').count()==initialPayments
   assert ec.entity.find(registry).count()==initialRegs
   boolean applicationBegan=ec.transaction.begin(600)
   try {
    def d=selected[0];createOne(d)
    def rec=ec.entity.find(registry).condition([companyPartyId:company,sourceId:d.sourceId]).one()
    boolean blocked=false
    try{call('create#mantle.account.payment.PaymentApplication',[paymentId:rec.paymentId,invoiceId:d.invoiceId,amountApplied:1G,appliedDate:ec.user.nowTimestamp])}
    catch(Exception e){blocked=(e.message+' '+ec.message.errorsString).contains('لا يمكن تسوية سند مستورد')}
    assert blocked
    result.applicationGuardVerified=true
   }finally{ec.transaction.rollback(applicationBegan,'Application guard verification',null);ec.message.clearErrors()}
   assert ec.entity.find('mantle.account.payment.Payment').count()==initialPayments
   assert ec.entity.find(registry).count()==initialRegs
   result.status='ROLLBACK_AND_IDEMPOTENCY_VERIFIED'
  }else{
   prepared.payments.collate(50).each{batch->
    boolean began=ec.transaction.begin(600)
    try{batch.each{createOne(it)};ec.transaction.commit(began)}
    catch(Throwable e){ec.transaction.rollback(began,'Voucher import failed',e);throw e}
   }
   result.status=mode=='verify'?'NATIVE_VOUCHERS_VERIFIED':'NATIVE_VOUCHERS_CREATED_NOT_POSTED'
  }
  assert ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('organizationPartyId',company).count()==initialLedger
  assert ec.entity.find('mantle.account.payment.PaymentApplication').count()==initialApps
  assert old.hash(new File(base,'source.back'))==sha
  result.nativePaymentCount=ec.entity.find(registry).condition([companyPartyId:company,sourceSha256:sha]).count()
  if(mode in ['apply','verify']){
   File tmp=File.createTempFile('voucher-result-','.json',base)
   Files.setPosixFilePermissions(tmp.toPath(),PosixFilePermissions.fromString('rw-------'))
   tmp.text=JsonOutput.toJson(result)
   Files.move(tmp.toPath(),new File(base,'native-voucher-result-v1.json').toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
  }
  result
 }
}
return NasqoNativeVoucherImportHelper
