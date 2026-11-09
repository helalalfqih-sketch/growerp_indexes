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
  assert reportFile.delete()
  def outside=new File(root,'outside.json');outside.text=JsonOutput.toJson(report)
  Files.createSymbolicLink(reportFile.toPath(),outside.toPath())
  try {helper.read(root,'TEST');assert false} catch(IllegalStateException expected){}
  Files.delete(reportFile.toPath())
  assert helper.read(root,'TEST').status=='RECONCILIATION_PENDING'
  println 'PASS: saved review scope, source integrity, projection, no writes, symlink rejection'
} finally {root.deleteDir()}
