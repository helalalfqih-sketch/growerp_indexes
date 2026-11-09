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
