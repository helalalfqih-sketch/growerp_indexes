#!/usr/bin/env python3
"""Source-contract regression check for external MCP transport selection.

This is intentionally a STATIC check. It does not claim to test the Google ADK
MCP handshake, OAuth, network access, or a deployed GrowERP server.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANAGER = ROOT / "moqui-adk/src/main/groovy/org/moqui/adk/AdkManager.groovy"


class ExternalMcpTransportContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        code = MANAGER.read_text(encoding="utf-8")
        cls.method = code.split("private static com.google.adk.tools.mcp.McpToolset buildExternalMcpToolset(def srv)", 1)[1].split(
            "/** Build the toolsets for every enabled external", 1
        )[0]

    def test_http_uses_streamable_transport(self):
        self.assertRegex(
            self.method,
            re.compile(
                r"if\s*\(transport\s*==\s*'http'\)\s*\{"
                r".*?StreamableHttpServerParameters\.builder\(\)"
                r".*?\.url\(url\).*?\.headers\(headers\).*?"
                r"new com\.google\.adk\.tools\.mcp\.McpToolset\(params\)",
                re.S,
            ),
        )

    def test_sse_is_preserved_and_unknown_transport_rejected(self):
        self.assertIn("srv.transport ?: 'sse'", self.method)
        self.assertIn("transport in ['sse', 'http']", self.method)
        self.assertIn("Unsupported external MCP transport", self.method)
        self.assertIn("SseServerParameters.builder()", self.method)

    def test_credentials_remain_per_server(self):
        self.assertIn("srv.headersJson", self.method)
        self.assertIn("headers[k as String] = v as String", self.method)
        self.assertNotIn("SystemSupport", self.method.split("Map<String, String> headers =", 1)[1])
        self.assertNotIn("getLoginKey(", self.method)


if __name__ == "__main__":
    unittest.main()
