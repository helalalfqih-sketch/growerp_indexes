import java.nio.file.Files
import groovy.json.JsonOutput
import java.security.MessageDigest
def helper=new GroovyShell(this.class.classLoader).evaluate(new File(args[0]))
def root=Files.createTempDirectory('saved-review-synthetic-').toFile()
def sourceData='synthetic-source-only'.getBytes('UTF-8')
def sha=MessageDigest.getInstance('SHA-256').digest(sourceData).encodeHex().toString()
try {
  def stage=new File(root,'TEST/'+sha);assert stage.mkdirs()
  new File(stage,'source.back').bytes=sourceData
  def manifest=[targetCompanyPartyId:'TEST',sourceSha256:sha,sourceTableCounts:[bill_details:3]]
  new File(stage,'manifest.json').text=JsonOutput.toJson(manifest)
  def report=[format:'nasqo-smart-accountant-reconciliation-v2',companyPartyId:'TEST',sourceSha256:sha,
    status:'REVIEW_ONLY_NOT_IMPORTED',financialDocumentsCreated:0,postedEntries:0,readyForAccountingImport:false,
    summary:[movements:2,cashboxes:1,sourceAccounts:1,accountGroups:1],accountGroups:[[id:1,name:'group']],
    cashboxes:[[id:1,name:'cash',cur:1]],accounts:[[name:'MUST_NOT_LEAK',phone:'MUST_NOT_LEAK']],
    movements:[[private:'MUST_NOT_LEAK']],currencyExceptions:[[sourceId:2,sourceCurrencyId:9,date:'2026-01-01',
    rawDain:'10.20',rawMadin:'0',sourceRowSha256:'MUST_NOT_LEAK']]]
  def reportFile=new File(stage,'reconciliation-v2.json')
  reportFile.text=JsonOutput.toJson(report)
  def before=new File(stage,'source.back').bytes
  def result=helper.read(root,'TEST')
  assert result.available && result.summary.movements==2 && result.invoiceLines==3
  assert result.currencyExceptions[0].rawDain=='10.20'
  assert !JsonOutput.toJson(result).contains('MUST_NOT_LEAK')
  assert !helper.read(root,'OTHER').available
  assert java.util.Arrays.equals(before,new File(stage,'source.back').bytes)
  try { helper.read(root,'../TEST');assert false } catch(IllegalArgumentException expected){}
  manifest.targetCompanyPartyId='OTHER';new File(stage,'manifest.json').text=JsonOutput.toJson(manifest)
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  manifest.targetCompanyPartyId='TEST';new File(stage,'manifest.json').text=JsonOutput.toJson(manifest)
  report.sourceSha256='wrong';reportFile.text=JsonOutput.toJson(report)
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  report.sourceSha256=sha;reportFile.text=JsonOutput.toJson(report)
  new File(stage,'source.back').text='changed'
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  new File(stage,'source.back').bytes=sourceData
  report.currencyExceptions[0].sourceCurrencyId=2
  report.summary.unknownCurrencyRows=1
  reportFile.text=JsonOutput.toJson(report)
  def decision=[format:'nasqo-accounting-decisions-v1',companyPartyId:'TEST',sourceSha256:sha,
    financialPostingAllowed:false,approval:[authority:'OWNER_INSTRUCTION'],currencyPolicy:[
      mode:'SINGLE_CURRENCY_AS_DECLARED',targetCurrencyUomId:'YER',sourceCurrencyIds:[1,2,3],
      amountTreatment:'PRESERVE_NUMERIC_VALUES',fxConversion:false]]
  def decisionFile=new File(stage,'import-decisions-v1.json')
  decisionFile.text=JsonOutput.toJson(decision)
  def approved=helper.read(root,'TEST')
  assert approved.currencyDecision.targetCurrencyUomId=='YER'
  assert approved.summary.unknownCurrencyRows==0 && approved.summary.sourceUnknownCurrencyRows==1
  assert approved.currencyExceptions[0].rawDain=='10.20'
  def digest={File f->MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex().toString()}
  def planFile=new File(stage,'draft-plan-v1.json');planFile.text='synthetic-private-plan'
  def pm=[format:'nasqo-accounting-draft-manifest-v1',companyPartyId:'TEST',sourceSha256:sha,
    decisionSha256:digest(decisionFile),planSha256:digest(planFile),
    status:'PREPARED_REQUIRES_MAPPING',currencyUomId:'YER',financialDocumentsCreated:0,postedEntries:0,
    readyForAccountingImport:false,summary:[readyForFinancialCreation:0,preparedDrafts:2],
    documentTypes:[[sourceBillType:3,sourceLabel:'فاتورة شراء',count:2,private:'MUST_NOT_LEAK']]]
  def pmFile=new File(stage,'draft-plan-manifest-v1.json');pmFile.text=JsonOutput.toJson(pm)
  assert helper.read(root,'TEST').preparedImport.summary.preparedDrafts==2
  assert !JsonOutput.toJson(helper.read(root,'TEST')).contains('MUST_NOT_LEAK')
  planFile.text='tampered'
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  planFile.text='synthetic-private-plan'
  decision.companyPartyId='OTHER';decisionFile.text=JsonOutput.toJson(decision)
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  decision.companyPartyId='TEST';decisionFile.text=JsonOutput.toJson(decision)
  assert java.util.Arrays.equals(before,new File(stage,'source.back').bytes)
  assert reportFile.delete()
  def outside=new File(root,'outside.json');outside.text=JsonOutput.toJson(report)
  Files.createSymbolicLink(reportFile.toPath(),outside.toPath())
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  Files.delete(reportFile.toPath())
  assert helper.read(root,'TEST').status=='RECONCILIATION_PENDING'
  println 'PASS: saved review scope, source integrity, projection, no writes, symlink rejection'
} finally {root.deleteDir()}
