# Optional internal MCP authentication

The HTTP assistant must stay usable for general questions and navigation when the
internal MCP service account cannot authenticate. Failed service-account login
never creates a UserLoginKey or falls back to guessed Basic credentials. New
credential attempts are limited to one per five minutes across agent configs.

Without a valid internal credential, no lazy internal McpToolset is attached.
The assistant instructions and description identify the missing ERP capability;
the model must not claim it queried records or completed business actions.
This fallback does not restore ERP tools or change the service account's status.

Synthetic HTTP runners never attach the shared administrative MCP connection:
their session context is not a substitute for caller-scoped ERP authorization.
They retain only time and caller-scoped saved-artifact helpers.
Legacy email, GitHub and Substack helpers accept model-supplied tenant IDs and are
not exposed to HTTP runners; external toolsets are also excluded until their
authorization and read-only scope are explicitly enforced. Internal/named agent
tool assembly is unchanged.

After legitimate service-account access is restored, reload affected internal
agent configs to attach their MCP connection. HTTP assistants stay restricted
until a caller-scoped, tenant-checked read adapter is available; they deliberately
do not upgrade to the shared administrative identity.

## Verification

Run `AdkOptionalMcpAuthUnit.groovy` and `AdkHttpRunnerPolicyUnit.groovy` against
the compiled ADK manager and deployed dependencies. The first covers false and
throwing logins, missing identity, credential cooldown/cache, absent MCP transport,
truthful fallback instructions and the restricted HTTP helper list.

A deployment still needs an authenticated end-to-end greeting through
`/adk/run_sse` and an ERP-data question to confirm the limitation is reported.
Anonymous ADK/MCP calls and cross-user session access must remain rejected.
