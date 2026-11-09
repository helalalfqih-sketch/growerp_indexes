#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
service=ET.parse(root/'backend/service/growerp/100/NasqoSavedAccountingReviewServices100.xml').getroot().find('service')
assert service.attrib['authenticate']=='true'
assert service.attrib['verb']=='get'
assert service.find('in-parameters') is None
xml=ET.tostring(service,encoding='unicode')
assert "GROWERP_M_ADMIN" in xml and "GROWERP_M_SYSTEM" in xml
call=service.find("./actions/service-call")
assert call.attrib['in-map']=="[userPartyId: ec.user.userAccount.partyId]"
assert call.attrib['out-map']=='related'
body=service.find('./actions/script').text
assert "related.userCompanyPartyId" in body and "company == allowedCompany" in body
assert "no-store, private" in body
for denied in ['disable-authz','internalLogin','create#','post#','INSERT ','UPDATE ']: assert denied not in xml
rest=ET.parse(root/'backend/service/growerp.rest.xml').getroot()
route=rest.find(".//resource[@name='SqliteImport']")
assert route.attrib['require-authentication']=='true'
review=route.find("./resource[@name='Review']")
assert [m.attrib['type'] for m in review.findall('method')]==['get']
assert route.find("./resource[@name='Preview']/method").attrib['type']=='post'
js=(root/'flutter/packages/admin/web/accounting-review.js').read_text()
assert "method: 'GET'" in js and "cache: 'no-store'" in js
assert 'innerHTML' not in js and 'insertAdjacentHTML' not in js
assert "textContent" in js and "storedSession() !== session" in js
assert "generation" in js and "activeController.abort()" in js
assert "localStorage.setItem" not in js and "console.log" not in js
assert 'accounting-review.js' in (root/'flutter/packages/admin/web/index.html').read_text()
print('PASS: saved accounting review authentication, company scope, GET-only, no-store, safe rendering')
