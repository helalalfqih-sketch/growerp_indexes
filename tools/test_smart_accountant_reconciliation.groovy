import java.sql.DriverManager
import java.nio.file.Files
def helper=new GroovyShell(this.class.classLoader).evaluate(new File(args[0]))
def reviewer=new GroovyShell(this.class.classLoader).evaluate(new File(args[1]))
def dir=Files.createTempDirectory('nasqo-account-mapping-test-').toFile()
def db=new File(dir,'synthetic.back')
Class.forName('org.sqlite.JDBC')
def c=DriverManager.getConnection('jdbc:sqlite:'+db.absolutePath)
try {
  def sqls=[
    'CREATE TABLE products(id INTEGER PRIMARY KEY)',
    'CREATE TABLE stores(id INTEGER PRIMARY KEY,name TEXT,state INTEGER)',
    'CREATE TABLE currencies(id INTEGER PRIMARY KEY,name TEXT,sympol TEXT,amount TEXT,amount2 TEXT,name2 TEXT)',
    'CREATE TABLE customers(id INTEGER PRIMARY KEY,name TEXT,type INTEGER)',
    'CREATE TABLE boxes(id INTEGER PRIMARY KEY,name TEXT)',
    'CREATE TABLE cboxes(id INTEGER PRIMARY KEY,name TEXT,cur INTEGER)',
    'CREATE TABLE macc(id INTEGER PRIMARY KEY,name TEXT,d1 TEXT,d2 TEXT)',
    'CREATE TABLE input_move(id INTEGER PRIMARY KEY,per_id INTEGER,dain TEXT,madin TEXT,details TEXT,year INTEGER,month INTEGER,day INTEGER,cur INTEGER,cboxid INTEGER,bill_type INTEGER,bstate INTEGER)',
    'CREATE TABLE bill_details(id INTEGER PRIMARY KEY)',
    'CREATE TABLE bin(id INTEGER PRIMARY KEY)',
    'CREATE TABLE store_trans(id INTEGER PRIMARY KEY)',
    "INSERT INTO boxes VALUES(1,'Source group A'),(2,'Source group B')",
    "INSERT INTO cboxes VALUES(1,'Cash A',1)",
    "INSERT INTO macc VALUES(1,'Measurement job','length','width')",
    "INSERT INTO currencies VALUES(1,'Known currency','K','1','1','')",
    "INSERT INTO customers VALUES(10,'Account A',1),(20,'Account B',2)",
    "INSERT INTO input_move VALUES(1,10,'10.10','0','Invoice',2025,12,30,1,0,2,1)",
    "INSERT INTO input_move VALUES(2,10,'0','0','Zero',2026,1,1,1,1,1,1)",
    "INSERT INTO input_move VALUES(3,20,'0','20.20','رصيد سابق',2026,1,1,1,0,1,1)",
    "INSERT INTO input_move VALUES(4,10,'5.10','0','تحويل الى حساب Account B - ',2026,1,2,1,0,1,1)",
    "INSERT INTO input_move VALUES(5,20,'0','4.10','تحويل من حساب Account A - ',2026,1,2,1,0,1,1)",
    "INSERT INTO input_move VALUES(6,10,'0','10.10','Received',2026,1,3,1,1,1,1)",
    "INSERT INTO input_move VALUES(7,10,'4','0','Unknown currency',2026,1,3,9,1,1,1)",
    "INSERT INTO input_move VALUES(8,10,'0.20','0','Fraction',2026,1,3,1,1,1,1)"
  ]
  c.createStatement().withCloseable { st -> sqls.each { st.execute(it) } }
} finally { c.close() }
try {
  def before=reviewer.digest(db)
  def p=helper.preview(db)
  assert p.accountingReview.sourceCashboxes==1
  assert p.accountingReview.sourceAccountGroupCount==2
  assert p.accountingReview.sourceMeasurementAccounts==1
  assert p.accountingReview.sourceChartOfAccountsRows==null
  assert p.accountingReview.sourceAccountGroups*.sourceGroupName==['Source group A','Source group B']
  def r=reviewer.review(db,'TEST')
  assert reviewer.digest(db)==before
  assert r.summary.movements==8
  assert r.summary.unknownCurrencyRows==1
  assert r.summary.openingLabelRows==1
  assert r.summary.unbalancedTransferPairs==1
  assert r.transferPairCandidates[0].difference==1.00G
  assert r.movements.every{it.approvedForImport==false}
  assert r.movements*.sourceKey.unique().size()==8
  assert r.summary.rawCashVoucherSides==[MADIN_ONLY:1,DAIN_ONLY:2]
  assert r.sourceAccountBalances.find{it.sourceAccountId==10 && it.sourceCurrencyId==1}.rawNetDainMinusMadin==5.30G
  assert reviewer.review(db,'TEST')==r
  def other=reviewer.review(db,'OTHER')
  assert other.movements[0].sourceKey!=r.movements[0].sourceKey
  // Editing the same source ID in a later export retains its identity and changes fingerprint.
  c=DriverManager.getConnection('jdbc:sqlite:'+db.absolutePath)
  try { c.createStatement().withCloseable{it.execute("UPDATE input_move SET dain='0.30' WHERE id=8")} }
  finally {c.close()}
  def changed=reviewer.review(db,'TEST')
  assert changed.movements[-1].sourceKey==r.movements[-1].sourceKey
  assert changed.movements[-1].sourceRowSha256!=r.movements[-1].sourceRowSha256
  assert changed.sourceSha256!=r.sourceSha256
  try { reviewer.review(db,'../escape'); assert false } catch(IllegalArgumentException expected) {}
  // Optional historical groups can lack names; preview must still work without inventing labels.
  c=DriverManager.getConnection('jdbc:sqlite:'+db.absolutePath)
  try { c.createStatement().withCloseable{it.execute('DROP TABLE boxes');it.execute('CREATE TABLE boxes(id INTEGER PRIMARY KEY)')} }
  finally {c.close()}
  assert helper.preview(db).accountingReview.sourceAccountGroups==[]
  println 'PASS: source structure, exact decimals, transfer difference, scope, fingerprints, read-only'
} finally {dir.deleteDir()}
