#!/usr/bin/env python3
"""Patch ONLY the ERP site in the existing VPSDime Caddyfile.

Run on a COPY and validate with Caddy before touching the live root-owned file.
Other domains, their routes and TLS data must not be changed.
"""
from pathlib import Path
import argparse
import re

SITE_HEADER = re.compile(r"^erp\.indexesstore\.com\s*\{\s*$")
OLD_DEFAULT = re.compile(
    r"(?m)^[ \t]*handle \{\r?\n"
    r"[ \t]*redir / /vapps 302\r?\n"
    r"[ \t]*reverse_proxy erp-moqui:80\r?\n"
    r"[ \t]*\}"
)
NEW_DEFAULT = """    # NASQO real ERP: API + WebSockets to Moqui, app UI to Flutter Web.
    @nasqoApi path /rest/* /rpc/* /status /vapps* /chat* /notws*
    handle @nasqoApi {
        reverse_proxy nasqo-backend:80
    }
    handle {
        reverse_proxy nasqo-web:80
    }"""


def patch_caddy(source: str) -> str:
    lines = source.splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if SITE_HEADER.fullmatch(line.strip())]
    if len(matches) != 1:
        raise ValueError("Expected exactly one erp.indexesstore.com Caddy site")
    start = matches[0]
    # A top-level, unindented closing brace ends a site in the actual config.
    end = next(
        (i for i in range(start + 1, len(lines)) if lines[i].strip() == "}" and
         lines[i].lstrip() == lines[i]),
        None,
    )
    if end is None:
        raise ValueError("ERP site is not properly closed")
    block = "".join(lines[start:end + 1])

    # Safe to run a second time; never insert duplicate route definitions.
    if "reverse_proxy nasqo-web:80" in block and "erp-moqui:80" not in block:
        return source
    if block.count("reverse_proxy erp-moqui:80") < 2:
        raise ValueError("Unexpected ERP Caddy structure; no changes made")
    if len(OLD_DEFAULT.findall(block)) != 1:
        raise ValueError("Old /vapps handler not found exactly once")
    block = OLD_DEFAULT.sub(NEW_DEFAULT, block, count=1)
    block = block.replace("reverse_proxy erp-moqui:80",
                          "reverse_proxy nasqo-backend:80")
    if "erp-moqui:80" in block:
        raise ValueError("The ERP site still references the old backend")
    result = "".join(lines[:start]) + block + "".join(lines[end + 1:])
    if "reverse_proxy nasqo-web:80" not in result:
        raise ValueError("Missing NASQO frontend route")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing root-owned Caddyfile")
    parser.add_argument("destination", type=Path,
                        help="Candidate output; never overwrite live source")
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve():
        parser.error("Refusing to overwrite the live Caddyfile in place")
    before = args.source.read_text(encoding="utf-8")
    after = patch_caddy(before)
    args.destination.write_text(after, encoding="utf-8")
    print("Prepared NASQO Caddy site; other hosts unchanged:", args.destination)


if __name__ == "__main__":
    main()
