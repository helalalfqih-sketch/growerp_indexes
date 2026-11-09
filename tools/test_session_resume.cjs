const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const {hasSavedSession, resumeSavedSession} = require('../flutter/packages/admin/web/session-resume.js');
const savedAuth = JSON.stringify(JSON.stringify({apiKey:'synthetic-key', user:{userId:'TEST-USER'}}));
const savedKey = JSON.stringify('synthetic-key');
function environment(type='reload', auth=savedAuth, key=savedKey, href='https://erp.example.test/?googleLogin=success&utm_source=test#/acct-setup') {
  let writes = 0;
  const state = {router:'preserved'};
  const win = {
    location:{href}, performance:{getEntriesByType:()=>[{type}]},
    localStorage:{getItem:n=>n==='flutter.authenticate'?auth:key},
    history:{state, replaceState:(next, _, url)=>{assert.equal(next,state);writes++;win.location.href=new URL(url,href).href;}}
  };
  return {win, writes:()=>writes};
}
test('reload keeps saved credentials and removes only the stale callback flag', () => {
  const e=environment();
  assert.equal(resumeSavedSession(e.win),true);
  assert.equal(e.win.location.href,'https://erp.example.test/?utm_source=test#/acct-setup');
  assert.equal(e.win.localStorage.getItem('flutter.authenticate'),savedAuth);
  assert.equal(e.win.localStorage.getItem('flutter.apiKey'),savedKey);
  assert.equal(resumeSavedSession(e.win),false);
  assert.equal(e.writes(),1);
});
test('history navigation can restore a stored session', () => {
  assert.equal(resumeSavedSession(environment('back_forward').win),true);
});
test('fresh Google callback always reaches the normal server verification flow', () => {
  const e=environment('navigate');assert.equal(resumeSavedSession(e.win),false);assert.equal(e.writes(),0);
});
test('reload without a consistent saved session leaves the normal login flow intact', () => {
  for(const [auth,key] of [[null,null],[savedAuth,JSON.stringify('other-key')],['bad-json',savedKey],[JSON.stringify('{}'),savedKey],[JSON.stringify(JSON.stringify({apiKey:'registered',user:{userId:'TEST'}})),JSON.stringify('registered')]]) {
    assert.equal(hasSavedSession(auth,key),false);
    assert.equal(resumeSavedSession(environment('reload',auth,key).win),false);
  }
});
test('non-success and ordinary routes are unchanged', () => {
  for(const url of ['https://erp.example.test/#/acct-setup','https://erp.example.test/?googleLogin=registered#/','https://erp.example.test/?googleLogin=error#/']) {
    const e=environment('reload',savedAuth,savedKey,url);assert.equal(resumeSavedSession(e.win),false);assert.equal(e.writes(),0);
  }
});
test('blocked storage or history does not crash page startup', () => {
  const a=environment();a.win.localStorage.getItem=()=>{throw Error('blocked')};
  assert.equal(resumeSavedSession(a.win),false);
  const b=environment();b.win.history.replaceState=()=>{throw Error('blocked')};
  assert.equal(resumeSavedSession(b.win),false);
});
test('the production script executes before Flutter without writing credentials or making requests', () => {
  const e=environment();
  vm.runInNewContext(fs.readFileSync(require.resolve('../flutter/packages/admin/web/session-resume.js'),'utf8'), {window:e.win,URL});
  assert.equal(e.writes(),1);
  const html=fs.readFileSync(require.resolve('../flutter/packages/admin/web/index.html'),'utf8');
  const script=html.match(/<script\s+src="session-resume\.js[^"]*"[^>]*>/)[0];
  assert.ok(!/async|defer/.test(script));
  assert.ok(html.indexOf('session-resume.js')<html.indexOf('flutter_bootstrap.js'));
});
