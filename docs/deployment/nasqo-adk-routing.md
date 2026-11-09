# NASQO ADK endpoint

The installed client creates a session at `POST /adk/apps/moqui-adk/users/{userId}/sessions` and streams answers through `POST /adk/run_sse`. These paths must go to Moqui, not the Flutter/Nginx fallback. The Caddy matcher includes exactly `/adk` and `/adk/*`, preserves the path, and retains the HTTPS `api_key` allowlist.

The ADK servlet is a custom servlet and must enforce authentication itself. Requests require a current Moqui user in ADMIN, GROWERP_M_ADMIN, or AdkUsers and a server-derived owner party. Session paths, persisted sessions, state, and configuration access are pinned to that user and owner; configuration writes require an administrator. An absent/invalid login returns JSON 401. Cross-owner sessions and credentials are not reused.

HTTP chat uses a dedicated read-only runner for the current owner and only that owner's configured provider key. It does not borrow another tenant's key or runner. Changing/removing AI settings invalidates that cached runner. Existing internal and scheduled runner behavior is retained.

SSE requests wait through the final event before the servlet request/context closes. The Android client can continue using the existing APK after the server deployment.

## Verification

Tests execute without network, provider requests, or production database access:

- `moqui-adk/src/test/groovy/AdkServletSecurityUnit.groovy`: compile the actual servlet with stubbed execution context/manager and real Groovy, Jakarta Servlet, and SLF4J dependencies; pass the servlet source path as its argument.
- `moqui-adk/src/test/groovy/AdkHttpRunnerPolicyUnit.groovy`: run with the compiled patched manager and the normal Moqui runtime classpath.
- `python3 deploy/nasqo/vpsdime/test_cutover.py`: verifies actual Caddy adaptation and exact ADK backend matching, including upgrades of existing routes.
- Compile the patched servlet and manager against the deployed Moqui runtime JARs before replacing the ADK component.

Before exposing the proxy route, verify the running servlet rejects anonymous access to both configs and session endpoints. Public smoke checks should return JSON 401 for unauthenticated/invalid-key session and run requests, and 204 for same-origin/native OPTIONS. A successful response from an authenticated phone and an actual model response must be confirmed separately; negative auth probes do not prove a complete chat.
