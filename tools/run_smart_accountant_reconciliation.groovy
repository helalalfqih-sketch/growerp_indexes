import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import groovy.json.JsonOutput
if(args.length!=3) throw new IllegalArgumentException('Usage: reviewer.groovy source.back companyId helper.groovy')
def source=new File(args[0])
def parent=source.canonicalFile.parentFile.toPath()
if(Files.isSymbolicLink(source.toPath())) throw new IllegalArgumentException('Source symlinks are not allowed')
if(Files.getPosixFilePermissions(parent)!=PosixFilePermissions.fromString('rwx------'))
    throw new IllegalArgumentException('Report parent must be owner-private (0700)')
def helper=new GroovyShell(this.class.classLoader).evaluate(new File(args[2]))
def report=helper.review(source,args[1])
byte[] bytes=(JsonOutput.prettyPrint(JsonOutput.toJson(report))+'\n').getBytes('UTF-8')
def output=parent.resolve('reconciliation-v2.json')
if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)) {
    if(Files.isSymbolicLink(output) || !java.util.Arrays.equals(Files.readAllBytes(output),bytes))
        throw new IllegalStateException('Existing report differs; review before replacing it')
} else {
    def pending=Files.createTempFile(parent,'.reconciliation-', '.tmp')
    try {
        Files.write(pending,bytes)
        Files.setPosixFilePermissions(pending,PosixFilePermissions.fromString('r--------'))
        Files.move(pending,output,StandardCopyOption.ATOMIC_MOVE)
    } finally {Files.deleteIfExists(pending)}
}
assert Files.getPosixFilePermissions(output)==PosixFilePermissions.fromString('r--------')
println JsonOutput.toJson([status:report.status,summary:report.summary,
    sourceSha256:report.sourceSha256,outputSha256:helper.digest(output.toFile()),
    financialDocumentsCreated:0,postedEntries:0])
