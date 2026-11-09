const assert = require('node:assert/strict');
const test = require('node:test');
const {routeMatches, parseApiKey, project, endpoint} = require('../flutter/packages/admin/web/accounting-review.js');
test('accounting setup route accepts Google callback query without unrelated routes', () => {
  assert.equal(routeMatches('#/acct-setup'), true);
  assert.equal(routeMatches('#/acct-setup?googleLogin=success'), true);
  for(const hash of ['', '#/', '#/acct-setup-other', '#/customers']) assert.equal(routeMatches(hash), false);
});
test('only the existing application string session is accepted', () => {
  assert.equal(parseApiKey(JSON.stringify('synthetic-test-session')), 'synthetic-test-session');
  for(const raw of [null, '', '{}', '[]', '123', 'null', 'not-json', '""']) assert.equal(parseApiKey(raw), null);
});
test('read-only payload cannot claim a financial import succeeded', () => {
  const r = {available:true,readOnly:true,status:'REVIEW_ONLY_NOT_IMPORTED',readyForAccountingImport:false,financialDocumentsCreated:0,postedEntries:0};
  assert.equal(project({review:r}), r);
  assert.deepEqual(project({review:{available:false,status:'NO_SAVED_REVIEW'}}), {available:false,status:'NO_SAVED_REVIEW'});
  for (const mutation of [{postedEntries:1}, {financialDocumentsCreated:1}, {readOnly:false}, {readyForAccountingImport:true}, {status:'IMPORTED'}])
    assert.throws(() => project({review:{...r,...mutation}}));
  assert.throws(() => project({}));
  assert.equal(endpoint, '/rest/s1/growerp/100/SqliteImport/Review');
});

const fs = require('node:fs');
const vm = require('node:vm');
test('review button follows Flutter history navigation without hashchange', () => {
  const elements = new Map();
  const intervals = [];
  const element = () => ({
    hidden:false,open:false,children:[],
    append(...items){this.children.push(...items); for(const x of items)if(x.id)elements.set(x.id,x);},
    setAttribute(){},addEventListener(){},replaceChildren(){this.children=[];},
    close(){this.open=false;}
  });
  const window={location:{hash:'#/'},addEventListener(){}};
  const document={head:element(),body:element(),createElement:element,getElementById:id=>elements.get(id)};
  vm.runInNewContext(fs.readFileSync(require.resolve('../flutter/packages/admin/web/accounting-review.js'),'utf8'),
    {window,document,setInterval:callback=>{intervals.push(callback);return intervals.length;},clearInterval(){},setTimeout,clearTimeout,AbortController});
  const trigger=elements.get('nasqo-accounting-review-open');
  assert.equal(trigger.hidden,true);
  window.location.hash='#/acct-setup';intervals.forEach(run=>run());
  assert.equal(trigger.hidden,false);
  window.location.hash='#/customers';intervals.forEach(run=>run());
  assert.equal(trigger.hidden,true);
});
