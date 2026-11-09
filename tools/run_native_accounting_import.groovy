import groovy.json.JsonOutput
import groovy.xml.XmlParser
import groovy.xml.XmlUtil
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.moqui.impl.context.ExecutionContextFactoryImpl
if(args.length!=3 || !(args[2] in ['check','rollback','apply','verify'])) throw new IllegalArgumentException('Usage: runner userPartyId sourceSha256 check|rollback|apply|verify')
String runtime='/opt/moqui/runtime'
def conf=new XmlParser(false,false).parse(new File(runtime+'/conf/MoquiProductionConf.xml'))
def ensure={String n-> conf[n] ? conf[n][0] : conf.appendNode(n)}
ensure('service-facade').attributes()['scheduled-job-check-time']='0'
ensure('cache-list').attributes()['warm-on-start']='false'
ensure('tools').attributes().putAll(['empty-db-load':'none','on-start-load-types':'none','worker-pool-core':'2','worker-pool-max':'4'])
ensure('server-stats').attributes().putAll(['visit-enabled':'false','visitor-enabled':'false'])
new File(runtime+'/tmp').mkdirs()
def confFile=File.createTempFile('nasqo-import-','.xml',new File(runtime+'/tmp'))
Files.setPosixFilePermissions(confFile.toPath(),PosixFilePermissions.fromString('rw-------'))
confFile.text=XmlUtil.serialize(conf)
def ecf=null
int nativeExit=0
try {
 System.setProperty('moqui.runtime',runtime)
 System.setProperty('moqui.conf','tmp/'+confFile.name)
 def txDir=new File(runtime+'/tmp/nasqo-accounting-import')
 txDir.mkdirs()
 def txConfig=bitronix.tm.TransactionManagerServices.configuration
 txConfig.serverId='nasqo-accounting-import'
 txConfig.logPart1Filename=new File(txDir,'btm1.tlog').absolutePath
 txConfig.logPart2Filename=new File(txDir,'btm2.tlog').absolutePath
 txConfig.currentNodeOnlyRecovery=true
 ecf=new ExecutionContextFactoryImpl(runtime,'tmp/'+confFile.name)
 org.moqui.Moqui.dynamicInit(ecf)
 def ec=ecf.executionContext
 assert ecf.serviceFacade.jobRunner==null
 def admin=ec.entity.find('moqui.security.UserAccount').condition('partyId',args[0]).disableAuthz().one()
 assert admin && admin.disabled!='Y'
 assert ec.user.internalLoginUser(admin.username)
 assert ec.user.isInGroup('GROWERP_M_ADMIN')
 def endpoint=ecf.serviceFacade.restApi.getRootResourceNode('growerp').resourceMap['100']
  .resourceMap['ImportExport'].resourceMap['AccountingDrafts']
 assert endpoint?.methodMap?.get('post')?.serviceName=='growerp.100.NasqoNativeAccountingImportServices100.run#NativeAccountingImport'
 ec.artifactExecution.push('/growerp/100/ImportExport/AccountingDrafts',
  org.moqui.context.ArtifactExecutionInfo.AT_REST_PATH,
  org.moqui.context.ArtifactExecutionInfo.AUTHZA_CREATE,true)
 try {
 def result=ec.service.sync().name('growerp.100.NasqoNativeAccountingImportServices100.run#NativeAccountingImport')
  .parameters([sourceSha256:args[1],mode:args[2]]).call()
 if(ec.message.hasError())throw new IllegalStateException(ec.message.errorsString)
 println 'NASQO_RESULT '+JsonOutput.toJson(result.result)
 } finally {ec.artifactExecution.pop()}
} catch(Throwable e) {nativeExit=1;println('NASQO_FAILURE '+e.class.name+': '+e.message);e.printStackTrace()}
finally {if(ecf!=null){ecf.destroyActiveExecutionContext();ecf.destroy()};confFile.delete()}

System.exit(nativeExit)
