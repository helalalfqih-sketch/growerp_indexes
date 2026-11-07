#!/usr/bin/env python3
"""Generate the production web config without touching business records."""
import json
import os
import pathlib
import re
import sys
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent
domain = os.environ.get("NASQO_DOMAIN", "").strip().lower()
if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain):
    sys.exit("Invalid NASQO_DOMAIN")
if domain.endswith((".example.com", ".invalid", ".local", ".github.dev")) or "." not in domain:
    sys.exit("A real production domain is required, not a placeholder")
if len(domain) > 253 or ".." in domain:
    sys.exit("Invalid production domain")
base = "https://" + domain
config = {
    "appName": "NASQO ERP",
    "packageName": "",
    "version": "",
    "build": "",
    "applicationId": "AppAdmin",
    "backend": "moqui",
    "databaseUrl": base,
    "chatUrl": "wss://" + domain,
    "databaseUrlDebug": base,
    "chatUrlDebug": "wss://" + domain,
    "test": False,
    "singleCompany": "",
    "connectTimeout": 30,
    "receiveTimeout": 60,
}
dest = ROOT / "private" / "app_settings.json"
dest.parent.mkdir(mode=0o700, exist_ok=True)
dest.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
dest.chmod(0o600)
print("Prepared production web config (no customer/demo data):", dest)
print("Backend origin:", base)
