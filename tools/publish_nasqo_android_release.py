#!/usr/bin/env python3
"""Publish the verified ARM64 APK and update only the current web download link."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, time, urllib.request
base = Path("/home/chatgpt-admin/nasqo-cutover")
release = base / "releases/android-apk-1.22.9"
signed = release / "signed"
public = base / "releases/android-apk-1.22.7/public-apk"
meta = json.loads((signed / "release-verification.json").read_text())
ci = json.loads((release / "ci-test-success.json").read_text())
assert ci["conclusion"] == "success" and ci["screenReviewed"] is True
assert ci["runId"] == meta["buildRun"] == 42708701806
assert ci["sourceCommit"] == meta["sourceCommit"] == "a50950a281c688a340f824d0990636cf9f3febdb"
assert meta["package"] == "com.indexesstore.nasqo" and meta["version"] == "1.22.9"
row = next(item for item in meta["artifacts"] if item["abi"] == "arm64-v8a")
source = signed / row["file"]
def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for part in iter(lambda: stream.read(1048576), b""):
            digest.update(part)
    return digest.hexdigest()
assert sha(source) == row["sha256"] and source.stat().st_size == row["bytes"]
def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=120, check=True)
    return result.stdout
before = json.loads((release / "deployment-before.json").read_text())
for name in ["nasqo-production-backend-1", "nasqo-production-database-1"]:
    assert run(["docker","inspect",name,"--format","{{.Id}} {{.Config.Image}}"]).strip() == before[name]
current = run(["docker","inspect","nasqo-production-web-1","--format","{{.Config.Image}}"]).strip()
assert current == "nasqo/web:native-voucher-drafts-20261109", "Unexpected current web image"
current_html = run(["docker","exec","nasqo-production-web-1","cat","/usr/share/nginx/html/index.html"])
assert current_html == (release / "web-index-before.html").read_text(), "Web template changed during preparation"
target = public / row["file"]
if target.exists():
    assert sha(target) == row["sha256"], "Never overwrite an existing versioned APK"
else:
    temporary = public / ("." + target.name + ".publishing")
    shutil.copyfile(source, temporary)
    temporary.chmod(0o644)
    assert sha(temporary) == row["sha256"]
    os.replace(temporary, target)
(public / (target.name + ".sha256")).write_text(row["sha256"] + "  " + target.name + "\n")
compose = base / "compose.yaml"
old = compose.read_text()
assert old.count("image: nasqo/web:native-voucher-drafts-20261109") == 1
backup = release / "compose-before.yaml"
if not backup.exists():
    backup.write_text(old)
    backup.chmod(0o600)
new = old.replace("image: nasqo/web:native-voucher-drafts-20261109", "image: nasqo/web:android-release-20261110")
command = ["docker","compose","-p","nasqo-production","-f",str(compose),"up","-d","--no-deps","--force-recreate","--pull","never","web"]
try:
    compose.write_text(new)
    run(command)
    for attempt in range(6):
        try:
            with urllib.request.urlopen("https://erp.indexesstore.com/",timeout=10) as response:
                html = response.read().decode()
            assert "/downloads/" + target.name in html
            break
        except Exception:
            if attempt == 5: raise
            time.sleep(1)
    paths = ["/usr/share/nginx/html/main.dart.js","/usr/share/nginx/html/session-resume.js","/usr/share/nginx/html/accounting-review.js"]
    actual = run(["docker","exec","nasqo-production-web-1","sha256sum",*paths]).strip().splitlines()
    assert actual == before["webStaticDigests"], "Unexpected change to deployed web application code"
    for name in ["nasqo-production-backend-1","nasqo-production-database-1"]:
        assert run(["docker","inspect",name,"--format","{{.Id}} {{.Config.Image}}"]).strip() == before[name]
    digest = hashlib.sha256()
    count = 0
    url = "https://erp.indexesstore.com/downloads/" + target.name
    with urllib.request.urlopen(url,timeout=30) as response:
        assert response.status == 200
        for part in iter(lambda: response.read(1048576), b""):
            digest.update(part)
            count += len(part)
    assert digest.hexdigest() == row["sha256"] and count == row["bytes"]
    result = {"status":"DEPLOYED_VERIFIED","url":url,"bytes":count,"sha256":row["sha256"],"webImage":"nasqo/web:android-release-20261110","backendAndDatabaseUnchanged":True,"webApplicationCodeUnchanged":True}
    (release / "deployment-result.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,ensure_ascii=False))
except Exception:
    compose.write_text(old)
    run(command)
    print("WEB_DOWNLOAD_LINK_ROLLED_BACK")
    raise
