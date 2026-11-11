# External MCP integration: Streamable HTTP and OAuth

## Current implementation

GrowERP stores tenant-scoped external MCP servers in `moqui.adk.AdkMcpServer`.
`AdkManager.buildExternalMcpToolset` now selects the Google ADK 1.10.1 transport
according to `transport`:

- `sse` (legacy/default): `SseServerParameters` for an HTTP+SSE endpoint.
- `http`: `StreamableHttpServerParameters` for a Streamable HTTP endpoint.

External connections receive only that server's encrypted-at-rest configured headers.
They do **not** receive the internal `SystemSupport` session or API key.
Unknown transports fail closed. The API retains the existing tenant ownership guard.

## SerpApi OAuth gateway

The deployed SerpApi gateway uses Streamable HTTP at
`https://waha.indexesstore.com/serpapi/mcp`. Its authorization server exposes
Authorization Code + PKCE S256, dynamic client registration (DCR), token and
refresh-token flows, and a protected-resource metadata document.

**This change fixes MCP transport selection only. It does not implement an OAuth
client within GrowERP**. A static Authorization header is not OAuth authorization-code
support, and access tokens must not be copied from another application's session.

Before enabling this gateway for an agent, implement and validate an independent
per-tenant OAuth client integration with:

1. RFC 8414 authorization-server metadata, RFC 9728 protected-resource metadata,
   DCR client registration, PKCE S256 and explicit redirect URI allowlisting.
2. A user-driven consent/authorization redirect with validated state, PKCE verifier
   and callback handling. Never ask users to paste access or refresh tokens.
3. Encrypted per-tenant token storage, expiration handling, bounded refresh and
   revocation/disconnect; prevent refresh races and cross-tenant token use.
4. Per-request Authorization headers sourced from a current token, never a token
   generated for the internal `SystemSupport` account.
5. Read-only tool discovery, authorization-denial, reconnection, expiry, token
   refresh, network failure, and tenant-isolation end-to-end tests.
6. Verified compatibility with the *deployed* Moqui/ADK image, including OAuth
   callback origin, and a rollback plan that preserves database data.

Do not advertise this transport-only change as completing OAuth support.

## Verification

- `python3 scripts/check_external_mcp_transport.py` checks source-level routing
  and credential isolation (static regression check, not a live handshake).
- `mvn -B -ntp -f scripts/adk-transport-contract/pom.xml -DskipTests compile`
  checks that the exact Google ADK 1.10.1 API has both required builders and
  `McpToolset` overloads (compile-time contract, not an end-to-end test).
- Runtime validation still requires an isolated GrowERP backend that loads the
  compiled `moqui-adk` component and a separately authenticated test gateway.

**Production deployment, merging, OAuth authorization, and financial write access
are explicitly out of scope for this review change.**
