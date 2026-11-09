#!/usr/bin/env python3
"""Patch ERP routes and native API-key compatibility in the VPSDime Caddyfile.

Run on a COPY and validate with Caddy before touching the live root-owned file.
Other domains, their routes and TLS data are preserved. The :443 listener must
allow api_key for Moqui; Caddy still drops other underscore headers and the
ambiguous Api-Key alias. Requires Caddy with expected_underscore_headers support.
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
    @nasqoApi path /rest/* /rpc/* /status /vapps* /chat* /notws* /googleOAuth* /adk /adk/*
    handle @nasqoApi {
        reverse_proxy nasqo-backend:80
    }
    handle {
        reverse_proxy nasqo-web:80
    }"""


# Caddy 2.11.4+ drops underscore headers before routing. Moqui's native
# clients authenticate with api_key; a narrow :443 allowlist restores it.
# This is listener-wide (including other HTTPS hosts), not an ERP route.
API_KEY_SERVER = """    servers :443 {
        expected_underscore_headers api_key
    }
"""


def _block_end(lines: list[str], start: int) -> int:
    depth = 0
    for index in range(start, len(lines)):
        line = lines[index].strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("{"):
            depth += 1
        elif line == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("Unclosed Caddy block; no changes made")


def ensure_api_key_header(source: str) -> str:
    """Preserve api_key on HTTPS without enabling arbitrary underscore headers."""
    lines = source.splitlines(keepends=True)
    first = next((i for i, line in enumerate(lines)
                  if line.strip() and not line.lstrip().startswith("#")), None)
    if first is None:
        raise ValueError("Empty Caddy configuration")
    if lines[first].strip() != "{":
        return "{\n" + API_KEY_SERVER + "}\n\n" + source

    end = _block_end(lines, first)
    server_blocks = []
    for index in range(first + 1, end):
        match = re.fullmatch(r"\s*servers(?:\s+(\S+))?\s*\{\s*", lines[index])
        if match:
            address = match.group(1)
            if address not in (":443", ":80"):
                raise ValueError("Review existing Caddy server policy before adding api_key")
            if address == ":443":
                server_blocks.append(index)
    if len(server_blocks) > 1:
        raise ValueError("Expected at most one servers :443 block")
    if not server_blocks:
        lines.insert(end, API_KEY_SERVER)
        return "".join(lines)

    start = server_blocks[0]
    stop = _block_end(lines, start)
    allowlists = [i for i in range(start + 1, stop)
                  if re.match(r"\s*expected_underscore_headers(?:\s|$)", lines[i])]
    if len(allowlists) > 1:
        raise ValueError("Expected one underscore-header allowlist")
    if allowlists:
        index = allowlists[0]
        values = lines[index].split("#", 1)[0].split()[1:]
        if "api_key" not in [value.lower() for value in values]:
            raise ValueError("Review existing underscore-header allowlist before adding api_key")
        return source
    indent = re.match(r"\s*", lines[start]).group() + "    "
    lines.insert(start + 1, indent + "expected_underscore_headers api_key\n")
    return "".join(lines)


def _patch_erp_routes(source: str) -> str:
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
        # Existing deployments must also receive new backend-only routes.
        # ADK must reach the authenticated servlet, not the Flutter catch-all.
        matcher = re.compile(r"(?m)^([ \t]*@nasqoApi path )([^\r\n]+)(\r?)$")
        matches = list(matcher.finditer(block))
        if len(matches) != 1:
            raise ValueError("Missing unique NASQO API matcher")
        match = matches[0]
        paths = match.group(2).split()
        if "/adk*" in paths:
            raise ValueError("Review broad ADK route before patching")
        for path in ("/googleOAuth*", "/adk", "/adk/*"):
            if path not in paths:
                paths.append(path)
        block = (block[:match.start()] + match.group(1) + " ".join(paths) +
                 match.group(3) + block[match.end():])
        return "".join(lines[:start]) + block + "".join(lines[end + 1:])
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


def patch_caddy(source: str) -> str:
    return ensure_api_key_header(_patch_erp_routes(source))


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
    print("Prepared NASQO routes and HTTPS api_key compatibility:", args.destination)


if __name__ == "__main__":
    main()
