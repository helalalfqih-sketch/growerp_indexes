#!/usr/bin/env python3
"""Sign a previously verified NASQO release artifact with the persistent VPS key."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import zipfile

base = Path("/home/chatgpt-admin/nasqo-cutover")
work = base / "releases/android-apk-1.22.9"
tools = base / "releases/android-install-fix-20261109"
signing = base / "private/android-signing"
signed = work / "signed"
signed.mkdir(exist_ok=True, mode=0o700)
assert (signing / "nasqo-release.p12").is_file()
assert (signing / "keystore.pass").is_file()
env = dict(os.environ, LD_LIBRARY_PATH=str(tools))
runtime = [
    "docker", "run", "--rm", "--user", "1000:1000",
    "--network=none", "--read-only", "--cap-drop=ALL",
    "--security-opt=no-new-privileges", "--memory=512m", "--cpus=1",
    "-v", str(work) + ":/work:rw",
    "-v", str(tools) + ":/tools:ro",
    "-v", str(signing) + ":/signing:ro",
    "--entrypoint", "/opt/java/openjdk/bin/java",
    "nasqo/backend:native-voucher-drafts-20261109-r2",
    "-jar", "/tools/apksigner.jar",
]

def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, timeout=180, **kwargs)
    if result.returncode:
        raise RuntimeError("Release verification failed: " + result.stderr[-1500:] + result.stdout[-1500:])
    return result.stdout

results = []
fingerprints = set()
for abi, suffix in [("arm64-v8a", "arm64"), ("x86_64", "x86_64")]:
    source = work / "unsigned" / ("NASQO_ERP_Android_1.22.9_" + abi + "-unsigned.apk")
    target = signed / ("NASQO_ERP_Android_1.22.9_" + suffix + ".apk")
    assert source.is_file(), "Missing validated unsigned artifact"
    with zipfile.ZipFile(source) as archive:
        assert archive.testzip() is None
        cfg = json.loads(archive.read("assets/flutter_assets/assets/cfg/app_settings.json"))
        assert cfg["databaseUrl"] == "https://erp.indexesstore.com"
        assert cfg["googleLoginEnabled"] and cfg["googleRegistrationEnabled"]
    run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(source)], env=env)
    run(runtime + [
        "sign", "--ks", "/signing/nasqo-release.p12", "--ks-key-alias", "nasqo-release",
        "--ks-pass", "file:/signing/keystore.pass",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--min-sdk-version", "24",
        "--out", "/work/signed/" + target.name,
        "/work/unsigned/" + source.name,
    ])
    verification = run(runtime + ["verify", "--verbose", "--print-certs", "/work/signed/" + target.name])
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in verification
    fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", verification).group(1)
    fingerprints.add(fingerprint)
    (signed / (suffix + "-signature.txt")).write_text(verification)
    run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(target)], env=env)
    badging = run([str(tools / "aapt2"), "dump", "badging", str(target)], env=env)
    assert "name='com.indexesstore.nasqo'" in badging
    assert "versionName='1.22.9'" in badging
    assert ("native-code: '" + abi + "'") in badging
    assert "application-debuggable" not in badging
    (signed / (suffix + "-badging.txt")).write_text(badging)
    digest = hashlib.sha256()
    with target.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            digest.update(chunk)
    target.chmod(0o644)
    results.append({"file": target.name, "bytes": target.stat().st_size, "sha256": digest.hexdigest(), "abi": abi, "signerSha256": fingerprint})
assert len(fingerprints) == 1
fingerprint_file = signing / "public-certificate.sha256"
fingerprint = next(iter(fingerprints))
if fingerprint_file.exists():
    assert fingerprint_file.read_text().strip() == fingerprint, "Signing identity changed"
else:
    fingerprint_file.write_text(fingerprint + "\n")
    fingerprint_file.chmod(0o644)
(signed / "SHA256SUMS").write_text("".join(row["sha256"] + "  " + row["file"] + "\n" for row in results))
(signed / "release-verification.json").write_text(json.dumps({"package": "com.indexesstore.nasqo", "version": "1.22.9", "sourceCommit": "a50950a281c688a340f824d0990636cf9f3febdb", "buildRun": 42708701806, "artifacts": results}, indent=2) + "\n")
print(json.dumps({"verifiedSignedArtifacts": results}, ensure_ascii=False))
