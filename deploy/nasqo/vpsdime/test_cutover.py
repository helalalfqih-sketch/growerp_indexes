#!/usr/bin/env python3
"""Validate safe NASQO cutover config without contacting a live server."""
from pathlib import Path
import os
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from patch_caddy import patch_caddy

sample = """indexesstore.com {
    reverse_proxy shopify-app:3000
}
erp.indexesstore.com {
    encode zstd gzip
    handle /mcp* {
        rewrite * /rest/s1/mcp/protocol
        reverse_proxy erp-moqui:80
    }
    handle {
        redir / /vapps 302
        reverse_proxy erp-moqui:80
    }
}
waha.indexesstore.com {
    reverse_proxy waha:3000
}
"""
patched = patch_caddy(sample)
assert "reverse_proxy nasqo-web:80" in patched
assert patched.count("reverse_proxy nasqo-backend:80") == 2
assert "/mcp*" in patched and "/rest/s1/mcp/protocol" in patched
assert "redir / /vapps" not in patched
assert "reverse_proxy erp-moqui" not in patched
assert patched.startswith(sample[:sample.index("erp.indexesstore.com {")])
assert patched.endswith(sample[sample.index("waha.indexesstore.com {"):])
assert patch_caddy(patched) == patched, "Patching must be idempotent"
try:
    patch_caddy(sample.replace("    handle {\n        redir / /vapps 302\n", ""))
except ValueError:
    pass
else:
    raise AssertionError("Unexpected ERP route structure must abort")

compose_text = (HERE / "compose.yaml").read_text()
assert "indexes-waha_default" in compose_text
assert "postgres:17.2" in compose_text
assert "nasqo/backend:release-31e58d1" in compose_text
assert "nasqo/web:release-ae4562" in compose_text
assert "DB_DATA: NONE" in compose_text
assert "types=seed,seed-initial,install" in compose_text
assert "5432:5432" not in compose_text
assert "80:80" not in compose_text and "443:443" not in compose_text
assert "pull_policy: never" in compose_text
env = os.environ.copy()
env.update({
    "NASQO_DB_PASSWORD": "CI_ONLY_NOT_REAL",
    "NASQO_CRYPT_PASSWORD": "CI_ONLY_NOT_REAL2",
})
run = subprocess.run(
    ["docker", "compose", "-f", str(HERE / "compose.yaml"),
     "config", "--quiet"],
    env=env, capture_output=True, text=True,
)
if run.returncode:
    print(run.stderr[:1500], file=sys.stderr)
    sys.exit(run.returncode)
print("NASQO VPSDime cutover checks passed: same-domain API routing, shared Caddy, no exposed database")
