def helper=new GroovyShell().evaluate(new File(args[0]))
def make={type=2->
 def invoice=[sourceId:10,sourceBillType:type,sourceAccountId:20,sourceCurrencyId:1,date:'2026-01-01',
  rawDain:type==2?'100':'0',rawMadin:type==2?'0':'100']
 def voucher=[sourceId:11,sourceBillType:1,sourceAccountId:20,sourceCurrencyId:1,date:'2026-01-02',
  rawDain:type==2?'0':'50',rawMadin:type==2?'50':'0',sourceCashboxId:1,
  sourceStructure:'CASH_LINKED_SINGLE_SIDE',sourceRowSha256:'a'*64,
  holds:['ACCOUNT_MAPPING_PENDING','VOUCHER_DIRECTION_PENDING']]
 [plan:[format:'nasqo-accounting-draft-plan-v1',companyPartyId:'test',currencyUomId:'YER',fxConversion:false,
   cashboxes:[[id:1]],drafts:[invoice,voucher]],
  headers:['11':[details:'واصل من فاتورة رقم 10']],invoices:['10':[invoiceId:'invoice',invoiceTotal:100G,fromPartyId:type==2?'test':'party',toPartyId:type==2?'party':'test']]]
}
def run={x->helper.prepare(x.plan,x.headers,x.invoices)}
def r=run(make());assert r.summary.incoming==1 && r.payments[0].fromPartyId=='party' && r.payments[0].amount==50G
r=run(make(3));assert r.summary.outgoing==1 && r.payments[0].fromPartyId=='test'
def mutations=[
 {x->x.headers['11'].details='نقد'},
 {x->x.headers['11'].details='واصل من فاتورة رقم 999'},
 {x->x.plan.drafts[1].sourceAccountId=99},
 {x->x.plan.drafts[1].sourceCurrencyId=2},
 {x->x.plan.drafts[1].rawDain='50';x.plan.drafts[1].rawMadin='0'},
 {x->x.plan.drafts[0].rawMadin='100'},
 {x->x.plan.drafts[1].sourceCashboxId=99},
 {x->x.plan.drafts[1].date='2025-12-31'},
 {x->x.headers['11'].discount_amount='5'},
 {x->x.plan.drafts[0].sourceBillType=4},
 {x->x.plan.drafts[1].rawMadin='101'}
]
mutations.each{mutate->def x=make();mutate(x);assert run(x).payments.empty}
def x=make()
x.plan.drafts<<x.plan.drafts[1]+[sourceId:12,rawMadin:'60']
x.headers['12']=[details:'واصل من فاتورة رقم 10']
r=run(x);assert r.payments.empty && r.held.size()==2 && r.held.every{it.reason=='LINKED_PAYMENTS_EXCEED_INVOICE'}
x=make();x.plan.drafts<<x.plan.drafts[1]
try{run(x);assert false}catch(IllegalStateException expected){}
println 'Native voucher plan: incoming, outgoing, wrong direction, scope, dates, adjustment, returns, and aggregate limits passed'
