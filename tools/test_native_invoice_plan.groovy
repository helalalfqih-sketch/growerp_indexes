def helper=new GroovyShell().evaluate(new File(args[0]))
def account=[id:20,name:'Test source counterparty',type:1]
def row=[draftKey:'smart-accountant:C1:input_move:1',sourceId:1,sourceAccountId:20,sourceRowSha256:'a'*64,
 sourceBillType:2,sourceCurrencyId:2,sourceCashboxId:1,date:'2026-01-02',rawDain:'100',rawMadin:'100',
 sourceLabel:'Sale',holds:['ACCOUNT_MAPPING_PENDING'],lines:[[sourceKey:'line:1',quantity:2,unitPrice:'50',lineTotal:'100',
 sourceProductId:7,description:'',sourceUnit:'unit',sourceDiscount:'0',sourceBonus:'0']]]
def plan=[format:'nasqo-accounting-draft-plan-v1',companyPartyId:'C1',sourceSha256:'b'*64,
 currencyUomId:'YER',fxConversion:false,accounts:[account],drafts:[row]]
def approval=[format:'nasqo-native-draft-approval-v1',companyPartyId:'C1',sourceSha256:'b'*64,
 authority:'OWNER_INSTRUCTION',instruction:'Create drafts',postingAllowed:false,
 counterparties:[[sourceAccountId:20,sourceNameSha256:helper.digest(account.name)]]]
def products=['7':'Source product'];def headers=['1':[details:'Original note']]
def result=helper.prepare(plan,approval,products,headers)
assert result.invoices.size()==1
assert result.invoices[0].total==100G && result.invoices[0].fromCompany
assert result.invoices[0].items[0].quantity==2G && result.invoices[0].items[0].amount==50G
assert result.invoices[0].currencyUomId=='YER' && result.invoices[0].statusId=='InvoiceInProcess'
assert helper.prepare(plan,approval,products,headers)==result
def reject={Closure c->boolean rejected=false;try{c()}catch(Exception expected){rejected=true};assert rejected}
reject{helper.prepare(plan,approval+[companyPartyId:'other'],products,headers)}
reject{helper.prepare(plan,approval+[postingAllowed:true],products,headers)}
reject{helper.prepare(plan+[drafts:[row,row]],approval,products,headers)}
assert helper.prepare(plan,approval+[counterparties:[]],products,headers).invoices.empty
assert helper.prepare(plan+[drafts:[row+[rawDain:'99']]],approval,products,headers).invoices.empty
assert helper.prepare(plan,approval,products,['1':[discount:'1']]).invoices.empty
assert helper.prepare(plan+[drafts:[row+[sourceBillType:1]]],approval,products,headers).invoices.empty
assert helper.prepare(plan+[drafts:[row+[holds:['OPENING_BALANCE_REVIEW']]]],approval,products,headers).invoices.empty
def ret=helper.prepare(plan+[drafts:[row+[sourceBillType:4]]],approval,products,headers).invoices[0]
assert ret.invoiceTypeEnumId=='InvoiceReturn' && !ret.fromCompany && ret.statusId=='InvoiceIncoming'
println 'PASS_NATIVE_PLAN_SCOPE_AMOUNTS_TYPES_HOLDS_IDEMPOTENCY'
