import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import groovy.json.*
import java.sql.DriverManager
if(args.length!=3) throw new IllegalArgumentException('Usage: source.back companyId helper.groovy')
def source=new File(args[0])
def stage=source.canonicalFile.parentFile
if(Files.isSymbolicLink(source.toPath()) || Files.isSymbolicLink(stage.toPath()) ||
   Files.getPosixFilePermissions(stage.toPath())!=PosixFilePermissions.fromString('rwx------'))
    throw new IllegalStateException('Private source directory required')
def helper=new GroovyShell(this.class.classLoader).evaluate(new File(args[2]))
def parse={String name -> def f=new File(stage,name)
    if(Files.isSymbolicLink(f.toPath()) || !f.isFile() || f.length()>32L*1024*1024)
        throw new IllegalStateException('Unsafe private input')
    new JsonSlurper().parse(f,'UTF-8')
}
String before=helper.hash(source)
def report=parse('reconciliation-v2.json')
def policy=parse('import-decisions-v1.json')
def evidence=parse('mapping_evidence.json')
if(report.companyPartyId!=args[1] || report.sourceSha256!=before || stage.name!=before)
    throw new IllegalStateException('Source/company mismatch')
Class.forName('org.sqlite.JDBC')
def connection=DriverManager.getConnection('jdbc:sqlite:'+source.toURI()+'?mode=ro')
def plan
try {
 connection.createStatement().withCloseable{it.execute('PRAGMA query_only=ON')}
 def rows={String table -> connection.createStatement().withCloseable { st ->
  st.executeQuery('SELECT * FROM '+table+' ORDER BY id').withCloseable { rs ->
   def out=[];while(rs.next()){def row=[:];(1..rs.metaData.columnCount).each{
    row[rs.metaData.getColumnLabel(it)]=rs.getObject(it)};out<<row};out
  }
 }}
 plan=helper.prepare(report,policy,evidence,rows('bill_details'),rows('bill_details2'))
} finally {connection.close()}
assert helper.hash(source)==before
def save={String name,Map data ->
 byte[] bytes=(JsonOutput.toJson(data)+'\n').getBytes('UTF-8')
 def target=new File(stage,name).toPath()
 if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
  if(Files.isSymbolicLink(target) || !java.util.Arrays.equals(Files.readAllBytes(target),bytes))
   throw new IllegalStateException('Existing draft differs; reconcile before replacement')
 }else{
  def temp=Files.createTempFile(stage.toPath(),'.draft-plan-','.tmp')
  try{Files.write(temp,bytes);Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString('r--------'))
   Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)
  }finally{Files.deleteIfExists(temp)}
 }
 assert Files.getPosixFilePermissions(target)==PosixFilePermissions.fromString('r--------')
}
save('draft-plan-v1.json',plan)
def manifest=[format:'nasqo-accounting-draft-manifest-v1',companyPartyId:args[1],
 sourceSha256:before,decisionSha256:helper.hash(new File(stage,'import-decisions-v1.json')),
 planSha256:helper.hash(new File(stage,'draft-plan-v1.json')),status:plan.status,
 currencyUomId:plan.currencyUomId,summary:plan.summary,documentTypes:plan.documentTypes,
 financialDocumentsCreated:0,postedEntries:0,readyForAccountingImport:false]
save('draft-plan-manifest-v1.json',manifest)
println JsonOutput.toJson([status:plan.status,summary:plan.summary,
 sourceUnchanged:helper.hash(source)==before,planSha256:manifest.planSha256])
