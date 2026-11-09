#!/usr/bin/env python3
"""Validate safe NASQO cutover config without contacting a live server."""
from pathlib import Path
import os
import json
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
assert sample[:sample.index("erp.indexesstore.com {")] in patched[:patched.index("erp.indexesstore.com {")]
assert patched.endswith(sample[sample.index("waha.indexesstore.com {"):])
assert patch_caddy(patched) == patched, "Patching must be idempotent"
try:
    patch_caddy(sample.replace("    handle {\n        redir / /vapps 302\n", ""))
except ValueError:
    pass
else:
    raise AssertionError("Unexpected ERP route structure must abort")

# Native requests have no browser session cookie: api_key must survive Caddy.
assert patched.count("expected_underscore_headers api_key") == 1
assert "servers :443 {" in patched
# The already-cut-over route must also acquire the header compatibility setting.
without_globals = patched[patched.index("indexesstore.com {"):]
assert patch_caddy(without_globals) == patched
global_prefix = "{\n    email ops@example.invalid\n}\n\n"
with_global = patch_caddy(global_prefix + sample)
assert with_global.count("email ops@example.invalid") == 1
assert with_global.count("expected_underscore_headers api_key") == 1
assert patch_caddy(with_global) == with_global
existing_server = "{\n    servers :443 {\n        max_header_size 32KB\n    }\n}\n\n"
with_server = patch_caddy(existing_server + sample)
assert with_server.count("servers :443 {") == 1
assert "max_header_size 32KB" in with_server
assert with_server.count("expected_underscore_headers api_key") == 1
assert patch_caddy(with_server) == with_server
for conflicting in [
    "{\n    servers {\n        max_header_size 32KB\n    }\n}\n\n",
    "{\n    servers :443 {\n        expected_underscore_headers other_key\n    }\n}\n\n",
]:
    try:
        patch_caddy(conflicting + sample)
    except ValueError:
        pass
    else:
        raise AssertionError("Conflicting server policy must require review")

# Parse with the production Caddy version, so a misplaced/unsupported option
# cannot pass merely because its text happens to occur in the generated file.
for config in [patched, with_global, with_server,
               (HERE.parent / "Caddyfile").read_text().replace(
                   "{$NASQO_DOMAIN}", "nasqo.example.invalid")]:
    adapted = subprocess.run(
        ["docker", "run", "--rm", "-i", "--network=none",
         "caddy:2.11.7-alpine", "caddy", "adapt",
         "--config", "/dev/stdin", "--adapter", "caddyfile"],
        input=config, capture_output=True, text=True, check=True,
    )
    servers = json.loads(adapted.stdout)["apps"]["http"]["servers"].values()
    https = [server for server in servers if ":443" in server["listen"]]
    assert https and all(
        server.get("expected_underscore_headers") == ["api_key"]
        for server in https
    ), "Caddy must apply the exact api_key allowlist on HTTPS"

compose_text = (HERE / "compose.yaml").read_text()
assert "indexes-waha_default" in compose_text
assert "postgres:17.2" in compose_text
assert "nasqo/backend:arabic-fiscal-c08142f" in compose_text
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
print("NASQO VPSDime cutover checks passed: same-domain API routing, native api_key forwarding policy, shared Caddy, no exposed database")
