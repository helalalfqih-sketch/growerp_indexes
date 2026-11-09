import groovy.json.*
import java.sql.DriverManager
import java.security.MessageDigest
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

class NasqoNativeImportHelper {
 static String hash(File f) {
  def md=MessageDigest.getInstance('SHA-256');f.withInputStream{s->byte[] b=new byte[65536];int n;while((n=s.read(b))!=-1)md.update(b,0,n)}
  md.digest().encodeHex().toString()
 }
 static Map run(def ec,String company,String owner,String sha,String mode) {
  if(!(mode in ['check','rollback','apply','verify'])||!(sha==~/[a-f0-9]{64}/)||
     company!=System.getenv('NASQO_ACCOUNTING_STAGE_COMPANY')||!ec.user.isInGroup('GROWERP_M_ADMIN'))
   throw new IllegalStateException('Import scope/role rejected')
  File root=new File('/var/lib/nasqo-accounting-imports')
  File base=new File(root,company+'/'+sha)
  [root,new File(root,company),base].each{if(Files.isSymbolicLink(it.toPath()))throw new IllegalStateException('Unsafe private path')}
  def read={String name->File f=new File(base,name);if(!f.isFile()||Files.isSymbolicLink(f.toPath())||f.length()>67108864)throw new IllegalStateException('Unsafe import file');new JsonSlurper().parse(f)}
  if(hash(new File(base,'source.back'))!=sha)throw new IllegalStateException('Source integrity failed')
  def manifest=read('draft-plan-manifest-v1.json')
  def planFile=new File(base,'draft-plan-v1.json')
  def planHash=hash(planFile)
  def currencyDecision=read('import-decisions-v1.json')
  if(manifest.companyPartyId!=company||manifest.sourceSha256!=sha||manifest.planSha256!=planHash||
     manifest.decisionSha256!=hash(new File(base,'import-decisions-v1.json'))||
     currencyDecision.companyPartyId!=company||currencyDecision.sourceSha256!=sha||
     currencyDecision.currencyPolicy?.targetCurrencyUomId!='YER'||
     currencyDecision.currencyPolicy?.amountTreatment!='PRESERVE_NUMERIC_VALUES'||
     currencyDecision.currencyPolicy?.fxConversion!=false||currencyDecision.financialPostingAllowed!=false)
   throw new IllegalStateException('Approved source/currency manifest changed')
  def approval=read('native-draft-approval-v1.json')
  if(approval.planSha256!=planHash||approval.companyPartyId!=company||approval.sourceSha256!=sha)
   throw new IllegalStateException('Native approval integrity failed')
  def plan=read('draft-plan-v1.json')
  def headers=[:];def products=[:]
  Class.forName('org.sqlite.JDBC')
  def con=DriverManager.getConnection('jdbc:sqlite:file:'+new File(base,'source.back').absolutePath+'?mode=ro')
  try {
   con.createStatement().execute('PRAGMA query_only=ON')
   def rs=con.createStatement().executeQuery('SELECT id,name FROM products')
   while(rs.next())products[rs.getString(1)]=rs.getString(2);rs.close()
   rs=con.createStatement().executeQuery('SELECT id,details,discount,discount_amount,bonus,added,added2 FROM input_move')
   while(rs.next()){def row=[:];['id','details','discount','discount_amount','bonus','added','added2'].each{row[it]=rs.getString(it)};headers[row.id]=row};rs.close()
  } finally {con.close()}
  def planner=ec.resource.script('component://growerp/service/NasqoNativeInvoicePlan.groovy',null)
  def prepared=planner.prepare(plan,approval,products,headers)
  if(prepared.companyPartyId!=company||prepared.sourceSha256!=sha)throw new IllegalStateException('Company/source mismatch')
  def pref=ec.entity.find('mantle.ledger.config.PartyAcctgPreference').condition('organizationPartyId',company).one()
  if(pref?.baseCurrencyUomId!='YER')throw new IllegalStateException('Company currency changed')
  def companyParty=ec.entity.find('mantle.party.Party').condition('partyId',company).one()
  if(companyParty?.ownerPartyId!=owner)throw new IllegalStateException('Company owner changed')
  def result=[status:'CHECKED_NATIVE_DRAFTS',companyPartyId:company,sourceSha256:sha,currencyUomId:'YER',
   eligible:prepared.summary,created:0,existing:0,postedEntries:0,
   heldReasonCounts:prepared.held.collectMany{it.reasons}.countBy{it}]
  if(mode=='check')return result
  def call={String name,Map p->
   def r=ec.service.sync().name(name).parameters(p).call()
   if(ec.message.hasError())throw new IllegalStateException('Native service failed: '+name+' '+ec.message.errorsString)
   r
  }
  def registry='growerp.nasqo.AccountingImportDocument'
  def partyRegistry='growerp.nasqo.AccountingImportParty'
  def existingInvoice={d->
   def rec=ec.entity.find(registry).condition([companyPartyId:company,sourceId:d.sourceId]).one()
   if(!rec)return false
   if(rec.sourceSha256!=sha||rec.sourceRowSha256!=d.sourceRowSha256||rec.planSha256!=planHash||!rec.invoiceId)
    throw new IllegalStateException('Source identity conflict: '+d.sourceId)
   def inv=ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId',rec.invoiceId).one()
   def mappedParty=ec.entity.find(partyRegistry).condition([companyPartyId:company,sourceAccountId:d.sourceAccountId]).one()
   if(!inv||!mappedParty||inv.fromPartyId!=(d.fromCompany?company:mappedParty.partyId)||
      inv.toPartyId!=(d.fromCompany?mappedParty.partyId:company)||inv.externalId!=d.reference||inv.currencyUomId!='YER'||inv.invoiceTotal!=d.total||
      inv.invoiceTypeEnumId!=d.invoiceTypeEnumId||!(inv.statusId in ['InvoiceInProcess','InvoiceIncoming']))
    throw new IllegalStateException('Imported draft changed; review required: '+d.sourceId)
   if(ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('invoiceId',inv.invoiceId).count()!=0)
    throw new IllegalStateException('Imported draft has ledger entries')
   true
  }
  def createOne={d->
   if(existingInvoice(d)){result.existing++;return}
   if(mode=='verify')throw new IllegalStateException('Missing native draft: '+d.sourceId)
   if(ec.entity.find('mantle.account.invoice.Invoice').condition('externalId',d.reference).count())
    throw new IllegalStateException('Unregistered invoice reference already exists')
   call('create#'+registry,[companyPartyId:company,sourceId:d.sourceId,sourceSha256:sha,
    sourceRowSha256:d.sourceRowSha256,planSha256:planHash,sourceAccountId:d.sourceAccountId,
    sourceBillType:d.sourceBillType.toString(),sourceCurrencyId:d.sourceCurrencyId.toString(),
    sourceCashboxId:d.sourceCashboxId.toString(),rawDain:d.rawDain,rawMadin:d.rawMadin,
    creationStatus:'DRAFT_NEEDS_REVIEW',financialPostingApproved:'N',createdDate:ec.user.nowTimestamp])
   def sourceAccount=prepared.counterparties[d.sourceAccountId]
   def partyRec=ec.entity.find(partyRegistry).condition([companyPartyId:company,sourceAccountId:d.sourceAccountId]).one()
   String nameHash=planner.digest(sourceAccount.name.toString())
   String partyId
   if(partyRec){
    if(partyRec.sourceNameSha256!=nameHash)throw new IllegalStateException('Source account name changed')
    def p=ec.entity.find('mantle.party.Party').condition('partyId',partyRec.partyId).one()
    if(p?.ownerPartyId!=owner)throw new IllegalStateException('Counterparty scope changed')
    partyId=partyRec.partyId
   }else{
    String ext='smart-accountant:'+company+':account:'+d.sourceAccountId
    if(ec.entity.find('mantle.party.Party').condition('externalId',ext).count())throw new IllegalStateException('Unregistered source account exists')
    def p=call('create#mantle.party.Party',[partyTypeEnumId:'PtyOrganization',ownerPartyId:owner,
     pseudoId:'SAP'+company+'A'+d.sourceAccountId,externalId:ext,
     comments:'حساب طرف مستورد للمستندات المحاسبية من المحاسب الذكي؛ لا يتضمن اعتماد تصنيفه القانوني.'])
    partyId=p.partyId
    call('create#mantle.party.Organization',[partyId:partyId,organizationName:sourceAccount.name.toString().trim()])
    call('create#'+partyRegistry,[companyPartyId:company,sourceAccountId:d.sourceAccountId,
     sourceNameSha256:nameHash,partyId:partyId,createdDate:ec.user.nowTimestamp])
   }
   if(!ec.entity.find('mantle.party.PartyRole').condition([partyId:partyId,roleTypeId:d.counterpartyRole]).one())
    call('create#mantle.party.PartyRole',[partyId:partyId,roleTypeId:d.counterpartyRole])
   def inv=call('mantle.account.InvoiceServices.create#Invoice',[
    fromPartyId:d.fromCompany?company:partyId,toPartyId:d.fromCompany?partyId:company,
    invoiceTypeEnumId:d.invoiceTypeEnumId,currencyUomId:'YER',
    invoiceDate:java.sql.Timestamp.valueOf(d.date+' 00:00:00'),
    pseudoId:'SAP'+company+'I'+d.sourceId,externalId:d.reference,referenceNumber:d.reference,
    description:d.description,invoiceMessage:'مسودة مستوردة للمراجعة. لم تُنقل التسويات أو المدفوعات، ولم تُعتمد الضرائب أو الحسابات. '+d.sourceNote])
   call('update#'+registry,[companyPartyId:company,sourceId:d.sourceId,invoiceId:inv.invoiceId])
   d.items.each{item->call('mantle.account.InvoiceServices.create#InvoiceItem',[
    invoiceId:inv.invoiceId,itemTypeEnumId:item.itemTypeEnumId,quantity:item.quantity,
    amount:item.amount,description:item.description,otherPartyProductId:item.otherPartyProductId])}
   def actual=ec.entity.find('mantle.account.invoice.Invoice').condition('invoiceId',inv.invoiceId).one()
   if(actual.statusId!=d.statusId||actual.invoiceTotal!=d.total||actual.currencyUomId!='YER'||
      ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('invoiceId',inv.invoiceId).count()!=0)
    throw new IllegalStateException('Native draft verification failed: '+d.sourceId)
   result.created++
  }
  long initialLedger=ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('organizationPartyId',company).count()
  if(mode=='rollback'){
   long initialInvoices=ec.entity.find('mantle.account.invoice.Invoice').count()
   long initialParties=ec.entity.find('mantle.party.Party').count()
   def selected=prepared.invoices.groupBy{it.sourceBillType}.values().collect{it[0]}
   boolean began=ec.transaction.begin(600)
   try{
    selected.each{createOne(it)};selected.each{createOne(it)}
    assert result.created==selected.size() && result.existing==selected.size()
    def rec=ec.entity.find(registry).condition([companyPartyId:company,sourceId:selected[0].sourceId]).one()
    boolean blocked=false
    try{call('update#mantle.account.invoice.Invoice',[invoiceId:rec.invoiceId,statusId:'InvoiceFinalized'])}
    catch(Exception expected){blocked=true}
    assert blocked
    result.postingGuardVerified=true
   }finally{ec.transaction.rollback(began,'Native import rollback verification',null);ec.message.clearErrors()}
   assert ec.entity.find('mantle.account.invoice.Invoice').count()==initialInvoices
   assert ec.entity.find('mantle.party.Party').count()==initialParties
   result.status='ROLLBACK_AND_IDEMPOTENCY_VERIFIED'
  }else {
   prepared.invoices.collate(50).each{batch->
    boolean began=ec.transaction.begin(600)
    try{batch.each{createOne(it)};ec.transaction.commit(began)}
    catch(Throwable e){ec.transaction.rollback(began,'Native import batch failed',e);throw e}
    println 'NASQO_IMPORT_PROGRESS '+JsonOutput.toJson([created:result.created,existing:result.existing,total:prepared.invoices.size()])
   }
   result.status=mode=='verify'?'NATIVE_DRAFTS_VERIFIED':'NATIVE_DRAFTS_CREATED_NOT_POSTED'
  }
  assert ec.entity.find('mantle.ledger.transaction.AcctgTrans').condition('organizationPartyId',company).count()==initialLedger
  assert hash(new File(base,'source.back'))==sha
  result.nativeInvoiceCount=ec.entity.find(registry).condition([companyPartyId:company,sourceSha256:sha]).count()
  if(mode in ['apply','verify']){
   File f=new File(base,'native-import-result-v1.json')
   File tmp=File.createTempFile('native-result-','.json',base)
   Files.setPosixFilePermissions(tmp.toPath(),PosixFilePermissions.fromString('rw-------'))
   tmp.text=JsonOutput.toJson(result)
   Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
  }
  result
 }
}
return NasqoNativeImportHelper
