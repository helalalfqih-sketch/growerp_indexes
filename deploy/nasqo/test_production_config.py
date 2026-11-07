#!/usr/bin/env python3
"""Fail-closed static checks for NASQO's production deployment foundation."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent
repo = root.parents[1]
compose = (root / "compose.yaml").read_text()
docker = (root / "Dockerfile.moqui").read_text()
startup = (repo / "backend/deploy/initstart.sh").read_text()
config_source = (repo / "flutter/packages/growerp_core/lib/src/domains/common/functions/get_backend_url.dart").read_text()

assert "postgres:17.2" in compose
assert "DB_DATA: NONE" in compose, "Runtime must never install or demo-load data"
assert "types=seed,seed-initial,install" in compose, "System bootstrap required"
assert "types=seed,seed-initial,demo" not in compose
assert "5432:5432" not in compose, "PostgreSQL port must be private"
assert "webapp_allow_origins: https://" in compose
assert "backend.growerp.com" not in compose, "No upstream backend"
assert "growerp/growerp.git" not in docker, "Backend image must use owner's repo"
assert "Dockerfile.web" in compose
assert "growerp/growerp.git" not in (root / 'Dockerfile.web').read_text()
assert "git rev-parse HEAD" in docker and "NASQO_COMMIT" in docker
assert 'Refusing demo data in production' in startup
assert 'set -x' not in startup, "No shell tracing with secrets"
assert "final configuredBaseUrl = GlobalConfiguration().get('databaseUrl');" in config_source

example = (root / ".env.example").read_text()
for secret in ("NASQO_DB_PASSWORD", "NASQO_CRYPT_PASSWORD"):
    assert re.search(rf"(?m)^{secret}=\s*$", example), "Do not ship default secrets"
assert not (root / "private/app_settings.json").is_file(), "Private output must not be committed"

env = dict(os.environ)
env.update({
    "NASQO_DOMAIN": "erp.example.com",
    "NASQO_DB_PASSWORD": "INERT_VALIDATION_ONLY_NOT_FOR_DEPLOYMENT",
    "NASQO_CRYPT_PASSWORD": "INERT_VALIDATION_ONLY_NOT_FOR_DEPLOYMENT2",
    "NASQO_COMMIT": "a" * 40,
    "NASQO_IMAGE_TAG": "ci-only",
})
run = subprocess.run(
    ["docker", "compose", "-f", str(root / "compose.yaml"), "config", "--quiet"],
    env=env,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
)
if run.returncode:
    print("Compose validation failed:", run.stderr[:2000], file=sys.stderr)
    sys.exit(run.returncode)
print("NASQO production foundation lint passed: no demo loader, no exposed DB port, pinned owner source")
