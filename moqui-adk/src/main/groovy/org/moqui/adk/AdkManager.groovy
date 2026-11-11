/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moqui.adk

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.reactivex.rxjava3.core.Flowable
import org.moqui.context.ExecutionContextFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry facade over the Google ADK Java SDK.
 *
 * Supports multiple named agent configs (one per tenant/ownerPartyId).
 * Session state is persisted via MoquiSessionService (AdkSession + AdkSessionEvent entities)
 * so history survives Moqui restarts.
 *
 * Call initConfig() for each enabled AdkAgentConfig record.
 * All session/agent methods route to the correct Runner via the registry.
 */
class AdkManager {

    protected final static Logger logger = LoggerFactory.getLogger(AdkManager.class)

    static final String APP_NAME = 'moqui-adk'
    static final String DEFAULT_CONFIG = '__default__'

    /** Sanitize an agent name so it is valid as a Gemini function name.
     *  Gemini requires: starts with letter or underscore, alphanumeric + _ . : - only, max 128 chars. */
    private static String sanitizeAgentName(String raw) {
        if (!raw) return 'agent'
        // Replace any character that is NOT [a-zA-Z0-9_.:- ] with underscore, then spaces too
        String s = raw.replaceAll('[^a-zA-Z0-9_.:\\-]', '_')
        // Ensure starts with letter or underscore
        if (s && !Character.isLetter(s.charAt(0)) && s.charAt(0) != (char)'_') s = '_' + s
        // Truncate to 128
        if (s.length() > 128) s = s.substring(0, 128)
        return s ?: 'agent'
    }

    // configId → Runner (one per enabled AdkAgentConfig)
    private static final Map<String, Runner>   registry         = new ConcurrentHashMap<>()
    // configId → LlmAgent — kept alongside Runner so runOneOff can build a fresh Runner
    private static final Map<String, LlmAgent> agentRegistry    = new ConcurrentHashMap<>()
    // ownerPartyId → configId for per-tenant routing
    private static final Map<String, String>   tenantRegistry   = new ConcurrentHashMap<>()
    // sessionId → configId — in-memory cache rebuilt on demand from DB after restart
    private static final Map<String, String>   sessionOwn       = new ConcurrentHashMap<>()
    // sessionId → completed-turn count, for throttling rolling-memory summarisation
    private static final Map<String, Integer>  turnCounts       = new ConcurrentHashMap<>()
    // configIds currently mid-build — breaks team-membership cycles during coordinator assembly
    private static final Set<String>           buildingConfigs  = ConcurrentHashMap.newKeySet()
    // Summarise a session into AdkMemory every N completed turns (env-tunable). The
    // {memory} preamble of subsequent sessions is loaded from that summary (see
    // AdkDevServlet.buildContext), giving continuity across conversations.
    private static final int SUMMARIZE_EVERY_N_TURNS = {
        try { return Math.max(1, Integer.parseInt(System.getenv('ADK_SUMMARIZE_EVERY') ?: '2')) }
        catch (Exception ignore) { return 2 }
    }()
    // configId → {provider, apiKey} for providers we cannot build a model for yet
    private static final Map<String, Map>      providerRegistry = new ConcurrentHashMap<>()

    /** Providers with a BaseLlm implementation on the classpath. openai has none: google-adk
     *  ships Gemini and Claude only, so an openai agent still registers without a runner. */
    static final List<String> SUPPORTED_PROVIDERS = ['gemini', 'anthropic']

    /** Built-in fallbacks, used only when neither the SystemDefault row nor the environment
     *  names a model. Gemini value mirrors GeminiAiUtil.DEFAULT_MODEL on the backend. */
    static final String DEFAULT_GEMINI_MODEL    = 'gemini-3.8-flash'
    static final String DEFAULT_ANTHROPIC_MODEL = 'claude-sonnet-5'

    /** Model used when an agent row names no model: the GrowERP wide SystemDefault row
     *  (Support app -> System Defaults) when it holds a model for this provider, then the
     *  environment, then the built-in constant. Same precedence GeminiAiUtil uses for content
     *  generation, minus the per-tenant SystemSettings level (an ADK agent is per-tenant
     *  already: its own row names the model). */
    static String defaultModelFor(String provider) {
        String sysDefault = systemDefaultModelFor(provider)
        if (sysDefault) return sysDefault
        if (provider == 'anthropic') {
            return System.getenv('ANTHROPIC_MODEL') ?: System.getProperty('ANTHROPIC_MODEL') ?:
                DEFAULT_ANTHROPIC_MODEL
        }
        return System.getenv('GEMINI_MODEL') ?: System.getProperty('GEMINI_MODEL') ?:
            DEFAULT_GEMINI_MODEL
    }

    /** SystemDefault.aiModelName when that row serves [provider]; '' when unset, served by
     *  another provider, or unreadable (no EC yet, table not loaded). */
    private static String systemDefaultModelFor(String provider) {
        if (sharedSessionService == null) return ''
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def sd = ec.entity.find('growerp.general.SystemDefault')
                        .condition('defaultId', 'SYSTEM').one()
                String model = sd?.aiModelName as String
                if (!model) return ''
                String sdProvider = (sd.aiProvider ?:
                        (model.startsWith('claude') ? 'anthropic' : 'gemini')) as String
                return sdProvider == provider ? model : ''
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("defaultModelFor: SystemDefault lookup failed: ${e.message}")
            return ''
        }
    }

    /** The provider's key from the environment, for agents configured without one. */
    static String envKeyFor(String provider) {
        if (provider == 'anthropic') return System.getenv('ANTHROPIC_API_KEY') ?: ''
        return System.getenv('GOOGLE_API_KEY') ?: System.getenv('GOOGLE_GENAI_API_KEY') ?:
            System.getenv('GEMINI_API_KEY') ?: ''
    }

    /** The model to hand LlmAgent.model(): a BaseLlm carrying the key, or - for Gemini without
     *  a key - the plain model name, which LlmRegistry resolves using the environment. */
    private static def buildModel(String provider, String modelId, String apiKey) {
        if (provider == 'anthropic') {
            def client = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
                .apiKey(apiKey ?: envKeyFor('anthropic')).build()
            return new com.google.adk.models.Claude(modelId, client)
        }
        return apiKey ?
            com.google.adk.models.Gemini.builder().modelName(modelId).apiKey(apiKey).build() :
            modelId
    }

    private static volatile MoquiSessionService sharedSessionService
    // ADK ArtifactService backed by the AdkArtifact entity: large binaries (PDFs/images)
    // stay out of the conversation context; agents load them on demand (LoadArtifactsTool).
    private static volatile MoquiArtifactService sharedArtifactService
    private static volatile com.google.adk.tools.mcp.McpToolset mcpToolset
    // Set true at process exit (JVM shutdown hook / destroy). Guards tool-loading so an
    // in-flight google-adk McpToolset SSE connect isn't retried while the server tears down.
    private static volatile boolean shuttingDown = false
    private static final java.util.concurrent.atomic.AtomicBoolean shutdownHookRegistered =
            new java.util.concurrent.atomic.AtomicBoolean(false)
    // configId → per-agent McpToolset. Each carries an `adk_config_id` header so the
    // MCP governance gate can identify the calling agent and enforce its tenant/scope.
    private static final Map<String, com.google.adk.tools.mcp.McpToolset> configMcpToolsets = new ConcurrentHashMap<>()
    // configId → external (tenant-registered) McpToolsets attached to that agent. Closed and
    // rebuilt on reloadConfig; closed on destroy.
    private static final Map<String, List<com.google.adk.tools.mcp.McpToolset>> configExternalToolsets = new ConcurrentHashMap<>()
    private static volatile String mcpApiKey = null
    private static volatile long mcpKeyRetryAfterMs = 0L
    private static final long MCP_KEY_RETRY_DELAY_MS = 300_000L
    static  volatile Map<String, Object>  currentConfig = [:]

    private static final String SESSION_PREAMBLE = '''\
You are the NASQO (نسقو) assistant inside the NASQO ERP application.
When naming this product in Arabic, use «نسقو»; in other languages, use NASQO.
Keep this product identity even if earlier messages or memory use an older name.
Reply in the user's language. Use the product name in introductions and ordinary help.
Technical tool/service identifiers and underlying framework names remain technical references.

Current execution context (always up-to-date for this session):
  User ID          : {userId}
  Username         : {username}
  Full name        : {userFullName}
  Organization     : {organizationName}
  Company pseudo ID: {companyPseudoId}
  Owner party ID   : {tenantId}
  Time zone        : {timeZone}
  Locale           : {locale}

IDENTITY AND ACCOUNT QUESTIONS:
The context above describes the signed-in user, not you, the assistant.
- "من أنت؟", "من انت", "who are you?", and requests to introduce yourself ask about the
  assistant. Introduce yourself as the NASQO AI assistant, without account or company details.
  For example in Arabic: أنا مساعد نسقو بالذكاء الاصطناعي. أساعدك في استخدام النظام والتنقل بين شاشاته.
- "من أنا؟", "من انا", and "who am I?" ask about the signed-in user. Answer briefly in the
  user's language using the display name from userFullName when available. Mention the company
  only when relevant. Do not include usernames, email addresses, company IDs, or internal owner
  identifiers unless the user explicitly asks for that specific information.
- A question about the application's or system's name asks about NASQO (نسقو), not the account.
  A question about the company name asks about organizationName.
- For an explicit username, email, company-number, or internal-owner question, provide only the
  requested context field. Never invent missing account details. Do not call a tool for values
  already present in this authenticated context.
- A greeting receives a brief greeting in the user's language, without an account-data summary.
Do not confuse "you" (the assistant) with "I" (the signed-in user), even when earlier conversation
turns made that mistake. Do not reproduce the earlier English account-summary template.

CHAT TEXT FORMAT:
The chat displays ordinary replies as plain text. Do not wrap words in asterisks or use Markdown
emphasis, headings, or tables. Keep replies in the user's language.
When navigation is requested, still emit the exact fenced growerp-action block required below;
this is a machine-readable action, not ordinary chat formatting.

MEMORY (continuity across conversations):
{memory}

'''

    private static final String MCP_PREAMBLE = '''\
STABLE DOMAIN KNOWLEDGE (OKF):
The curated OKF knowledge bundle holds stable domain knowledge: entity/data-model definitions
with schemas and relationships, business structure, module relationships. For questions like
"what fields does X have?", "what is related/joined to X?", "how is the data model structured?"
use 'okf_index' to orient, 'okf_load_concept' (path, e.g. 'tables/OrderHeader.md') to read one
concept, and 'okf_follow' to see a concept's related concepts. Load only the concepts you need;
cite the concept path in your answer. Prefer OKF over searchKnowledge for these questions.

COMPANY KNOWLEDGE (RAG):
Use the 'searchKnowledge' tool to answer questions about THIS company's own documents, policies,
procedures or product information that are NOT in the live ERP data. Pass the user's question as
'query'. Answer ONLY from the returned passages and cite them; if nothing relevant is returned,
say you don't have that information rather than guessing. Use the Moqui tools for live ERP data
(orders, parties, products), OKF for stable data-model/domain structure, and searchKnowledge for
documents/policies and bulk/operational content.

MOQUI SERVICES — how to run business logic:
Service names (verb#Noun, e.g. generate#MasterContent) are NOT tools — never call one as a tool.
Run them with the 'moqui_execute_service' tool: {serviceName:"...", parameters:{...}}. Never invent
or guess a service name (no getPending#X, updateContent#X): when unsure of the exact name use
'moqui_search_services' with a noun keyword first, then 'moqui_get_service_details' for its
parameters, then execute exactly the returned name. Prompts ('moqui_prompts_get') are documentation
templates, not services.

'''

    private static final String NAVIGATION_PREAMBLE = '''\
SCREEN NAVIGATION — opening operational app screens
The NASQO front-end (Flutter) screens available in this session are listed in this
catalog (JSON: widgetName, description, keywords, parameters):
{screenCatalog}

When the user wants to reach or operate a screen — phrases like "enter/create/add",
"show/list/open/find", "edit", "approve", "receive" — choose the best-matching entry from
the catalog above (match the widgetName, keywords and description), respond with ONE short
sentence, then append a fenced action block the app executes (single object or an array):
```growerp-action
{"action":"navigate","widget":"<widgetName>","params":{...},"label":"<chip text>"}
```

Pick the action and widget from the catalog by intent:
- VIEW / LIST a kind of record → action "navigate" to the matching *List widget
  (omit `route`; the app resolves it from the widget name). e.g. "show products" →
  {"action":"navigate","widget":"ProductList"}.
- CREATE a new record → action "dialog" with that entity's *Dialog widget and NO id.
  e.g. "add a product" → {"action":"dialog","widget":"ProductDialog"}.
  PREFILL: read the field values from the USER'S message and put them in `params` (using the
  field names from that widget's catalog `parameters`), plus "_aiPrefill":true. The example
  below shows only the JSON SHAPE — the tokens in angle brackets are placeholders you MUST
  replace with the actual words from the user's message; never copy the placeholder text or any
  example name/email:
  {"action":"dialog","widget":"UserDialog","params":{"firstName":"FIRST_NAME_FROM_USER","lastName":"LAST_NAME_FROM_USER","email":"EMAIL_FROM_USER","role":"employee","_aiPrefill":true}}.
- OPEN / EDIT a specific record → action "dialog" with the *Dialog widget and the id in
  `params`, using the id parameter NAMED in that widget's catalog `parameters` (e.g.
  productId, partyId, locationId). e.g. "edit product DEMO_1" →
  {"action":"dialog","widget":"ProductDialog","params":{"productId":"DEMO_1"}}.
  You may also include field values to change alongside the id (same prefill rule).

Rules:
- Use only widgetNames present in the catalog; read each widget's `parameters` for the
  exact arg names it accepts (both id params and prefillable fields). Put inputs in `params`.
- `route` is optional and usually omitted (the app resolves it from the widget name).
- Emit the block ONLY when a screen should open; otherwise just answer in text.
- USE THE USER'S EXACT VALUES verbatim in `params`. Never invent, anonymize, or substitute a
  value the user gave — e.g. if the user says email info@hansbakker.com, the param MUST be
  "info@hansbakker.com", never a placeholder like test1@example.com.

For CREATE ("add"/"create"/"new"): build the directive ONLY from the values in the user's latest
message. Do NOT search for, resolve, open, or copy an existing record, and do NOT use any name or
email from these instructions or earlier turns — a new record uses ONLY what the user just typed.

For OPEN / EDIT of a record the user names (not by id): resolve its id with read-only tools first
(moqui_search_services / moqui_execute_service), then emit the directive with that id.

WRITES ARE USER-CONFIRMED: you only NAVIGATE / OPEN screens — never call a service that
performs a write (create/update/approve/receive/delete). The user submits the opened,
pre-filled dialog. Order/shipment specifics still work: "enter a sales order" →
{"widget":"SalesOrderList","params":{"openNew":true}}; "approve order <id>" →
{"widget":"SalesOrderList","params":{"finDocId":"<id>","presetStatus":"approved"}};
"receive shipment <id>" → {"widget":"IncomingShipmentList","params":{"finDocId":"<id>"}}.

'''

    static final String CONTEXT_PREAMBLE = SESSION_PREAMBLE + MCP_PREAMBLE + NAVIGATION_PREAMBLE

    private static final String MCP_UNAVAILABLE_INSTRUCTIONS = '''\
ERP TOOL AVAILABILITY:
The internal Moqui MCP tools are unavailable in this session.
You can answer general questions, use only the other tools actually supplied, and help the user
navigate with the screen catalog. You cannot query live ERP records, company knowledge, or OKF
through MCP in this session. Never invent data, claim you checked records, or claim an ERP action
succeeded. When a request needs unavailable tools, explain this limitation clearly and offer the
matching screen. Do not mention credentials or suggest bypassing account access controls.

'''

    private static String contextPreambleForMcp(boolean available) {
        if (available) return CONTEXT_PREAMBLE
        // Keep identity and navigation, but do not advertise tools absent from this runner.
        String navigation = NAVIGATION_PREAMBLE.replace(
                'resolve its id with read-only tools first\n(moqui_search_services / moqui_execute_service), then emit the directive with that id.',
                'ask the user for its record ID or open the matching list screen. Never invent an ID.')
        return SESSION_PREAMBLE + MCP_UNAVAILABLE_INSTRUCTIONS + navigation
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Register (or replace) one named agent config.
     * ownerPartyId may be null for a global/default config.
     */
    static synchronized void initConfig(String configId, String ownerPartyId,
                                        String agentName, String modelName,
                                        String instruction, String apiKey,
                                        String llmProvider = 'gemini',
                                        String description = null) {
        String effectiveProvider = llmProvider ?: 'gemini'

        // Providers without a BaseLlm implementation: register in the side registry so the
        // agent is at least visible, but there is no runner to answer with.
        if (!SUPPORTED_PROVIDERS.contains(effectiveProvider)) {
            providerRegistry[configId] = [provider: effectiveProvider, apiKey: apiKey ?: '']
            // Only a general (unnamed) interactive agent claims the tenant's chat route;
            // named/scheduled agents (e.g. CI Monitor) must not hijack interactive chat.
            if (ownerPartyId && !agentName) tenantRegistry[ownerPartyId] = configId
            logger.info("Provider '${effectiveProvider}' registered for configId='${configId}' (tenant='${ownerPartyId ?: 'global'}') — no runner, routing not implemented")
            return
        }

        if (!apiKey && !envKeyFor(effectiveProvider)) {
            logger.warn("No ${effectiveProvider} API key provided or found in environment. Disabling ADK agent configId='${configId}'.")
            return
        }

        // google-genai reads the key from the environment, so a key from System Setup has to
        // reach it another way; the Anthropic client takes its key directly.
        if (apiKey && effectiveProvider == 'gemini') System.setProperty('GOOGLE_API_KEY', apiKey)

        try {
            // Read this agent's scoping so the in-process FunctionTools (Email/GitHub) honour it
            // too — a read-only agent gets no write tools.
            String toolMode = lookupToolMode(configId)
            // A task-loop agent gets no in-process write tools (email/GitHub/Substack): those
            // bypass writePolicy, and the loop reports to the requester itself. Its writes all
            // go through the governed moqui_execute_service tool.
            boolean allowWrites = toolMode != 'readOnly' && !lookupLoopEnabled(configId)

            // Per-agent MCP toolset: identical to the shared one but tagged with this config's
            // id (and owner) so the MCP governance gate / searchKnowledge can resolve the
            // calling agent and its tenant. The dangerous action itself is still gated
            // server-side by govern#AgentAction; toolMode here just keeps the write-capable
            // moqui_execute_service tool out of a read-only agent's context.
            def agentMcpToolset = buildMcpToolset(configId, ownerPartyId, toolMode)

            String modelId = modelName ?: defaultModelFor(effectiveProvider)
            def modelArg = buildModel(effectiveProvider, modelId, apiKey)
            LlmAgent agent

            // In-process FunctionTools (read-only set + writes when allowed), plus the MCP toolset.
            boolean httpInteractive = configId?.startsWith('HTTP_INTERACTIVE_')
            List allTools = httpInteractive ? assembleHttpFunctionTools() :
                    assembleFunctionTools(allowWrites,
                            effectiveProvider == 'gemini' && lookupWebSearch(configId), modelArg)
            if (agentMcpToolset) allTools.add(agentMcpToolset)
            // External (tenant-registered) MCP servers attached to this agent — wrapped so a
            // tool name that collides with the internal server or an earlier external server
            // gets logged instead of silently shadowing (see CollisionLoggingToolset).
            Set<String> seenMcpToolNames = Collections.synchronizedSet(new HashSet<>(INTERNAL_MCP_TOOL_NAMES))
            int externalServerIdx = 0
            List externalToolsets = httpInteractive ? [] : loadExternalMcpToolsets(configId, ownerPartyId)
            externalToolsets.each { ts ->
                externalServerIdx++
                allTools.add(new CollisionLoggingToolset(ts, configId, "external MCP server #${externalServerIdx}", seenMcpToolNames))
            }

            if (!agentName) {
                agent = LlmAgent.builder()
                    .name('growerp-agent')
                    .description(agentMcpToolset ?
                            'NASQO ERP assistant with access to Moqui MCP tools' :
                            'NASQO assistant for general help and navigation; internal ERP tools unavailable')
                    .instruction(contextPreambleForMcp(agentMcpToolset != null) + (agentMcpToolset ? '''\
You are NASQO Assistant, an AI agent for the NASQO (نسقو) ERP system.
Answer the user's questions using the available Moqui MCP tools.

How to use the Moqui tools:
- Only 'growerp.*' services are callable. NEVER guess or invent a service name and never call
  a 'mantle.*', 'org.moqui.*' or other non-growerp service — it will be rejected. ALWAYS find
  the real service with 'moqui_search_services' first, then call exactly that name.
- Use 'moqui_search_services' with a keyword query to find relevant services. e.g. for orders,
  shipments, invoices or payments search "FinDoc" — the sales-order list is
  'growerp.100.FinDocServices100.get#FinDoc' with parameters {docType:"order", sales:true}.
- Use 'moqui_get_service_details' to learn a service's parameters.
- Use 'moqui_execute_service' to run a service only when that tool is supplied.
''' : '') + (httpInteractive ?
                            "- Use 'getCurrentTime' only when asked about the current time in a city.\n" : '''\
- Use 'getCurrentTime' only when asked about the current time in a city.
- Use 'sendEmail' to send email; always pass ownerPartyId from your context ({tenantId}). Returns an error if email is not configured for this tenant.
- Use 'readEmails' to poll and read recent incoming email; always pass ownerPartyId from your context ({tenantId}). Returns an error if email is not configured.
- Use the GitHub tools ('getLatestTestRun', 'getTestExceptions', 'getMainSha', 'getFileContent', 'createBranch', 'updateFileContent', 'createPullRequest', 'addComment') to interact with GitHub. Always pass ownerPartyId from your context ({tenantId}) so that the tenant's GitHub token configuration is retrieved.

''' ) + '''\
CRITICAL tool-use rules — follow exactly:
- After a tool returns a result, NEVER call that same tool again with the same arguments.
- As soon as a tool result contains the information needed, STOP calling tools and write
  a final, concise natural-language answer for the user (e.g. list the service names you found).
- Make at most a few tool calls per question; if you already have an answer, just answer.
''')
                    .model(modelArg)
                    .tools(allTools)
                    .build()
            } else {
                agent = LlmAgent.builder()
                        .name(sanitizeAgentName(agentName))
                        // A non-null description is REQUIRED when this agent is wrapped as an
                        // AgentTool by a coordinator (AgentTool.declaration NPEs on null), and it
                        // tells the coordinator's LLM when to delegate here.
                        .description(description ?: agentName ?: 'NASQO agent')
                        .model(modelArg)
                        .instruction(contextPreambleForMcp(agentMcpToolset != null) + (instruction ?: '') +
                                (agentMcpToolset ? '' : MCP_UNAVAILABLE_INSTRUCTIONS))
                        .tools(allTools)
                        .build()
            }

            // Phase 4: a coordinator/workflow config replaces its base agent with an orchestrator
            // that delegates to its team members (router via AgentTool — each member keeps its own
            // McpToolset, so its scope/writePolicy/owner-pin still apply when the coordinator calls it).
            def orch = lookupOrchestration(configId)
            boolean isCoordinator = orch != null && (orch.role == 'coordinator' || orch.role == 'workflow')
            if (isCoordinator) {
                def orchestrated = buildOrchestrator(configId, ownerPartyId, agentName, modelArg, orch)
                if (orchestrated != null) agent = orchestrated
            }

            Runner runner = Runner.builder()
                    .agent(agent)
                    .appName(APP_NAME)
                    .sessionService(sharedSessionService ?: new InMemorySessionService())
                    .artifactService(sharedArtifactService ?: new com.google.adk.artifacts.InMemoryArtifactService())
                    .build()

            registry[configId]      = runner
            agentRegistry[configId] = agent
            // A general (unnamed) interactive agent OR a coordinator claims the tenant's chat route;
            // plain named/scheduled specialists (e.g. CI Monitor) must not hijack interactive chat.
            if (ownerPartyId && (!agentName || isCoordinator)) tenantRegistry[ownerPartyId] = configId
            // modelId, not modelName: a row that names no model still reports what it runs on
            currentConfig = [agentName: agent.name(), modelName: modelId, configId: configId]
            logger.info("ADK agent '${agent.name()}' registered as configId='${configId}' (tenant='${ownerPartyId ?: 'global'}')")
        } catch (Throwable t) {
            logger.warn("Failed to initialize ADK agent configId='${configId}': ${t.message}", t)
        }
    }

    /** Backward-compat: register a single global config. */
    static void init(String agentName, String modelName, String instruction, String apiKey) {
        initConfig(DEFAULT_CONFIG, null, agentName, modelName, instruction, apiKey)
    }

    /**
     * Generate a UserLoginKey only after the dedicated account successfully authenticates.
     * A disabled/missing service account is not bypassed with guessed Basic credentials.
     */
    private static String generateMcpApiKey(ExecutionContextFactory ecf) {
        // Must use a fresh thread so this never changes or destroys the HTTP caller's EC.
        String[] result = [null]
        Thread t = new Thread({
            def ec = ecf.getExecutionContext()
            try {
                boolean loggedIn = ec.user.internalLoginUser('SystemSupport')
                if (!loggedIn || !ec.user.userId || ec.user.username != 'SystemSupport') {
                    logger.warn('Internal MCP authentication unavailable; ERP tools will be omitted')
                    return
                }
                result[0] = ec.user.getLoginKey(8760f)
                if (result[0]) logger.info('Generated internal MCP service-account key')
            } catch (Exception e) {
                logger.warn('Internal MCP authentication unavailable; ERP tools will be omitted')
            } finally {
                ec.destroy()
            }
        }, 'adk-mcpkey-gen')
        t.daemon = true
        t.start()
        t.join(5000L)
        return result[0]
    }

    private static synchronized String resolveMcpApiKey(ExecutionContextFactory ecf) {
        if (mcpApiKey) return mcpApiKey
        long now = System.currentTimeMillis()
        if (ecf == null || now < mcpKeyRetryAfterMs) return null
        // Reserve the cooldown before trying, including failures and a slow login thread.
        mcpKeyRetryAfterMs = now + MCP_KEY_RETRY_DELAY_MS
        mcpApiKey = generateMcpApiKey(ecf)
        if (mcpApiKey) mcpKeyRetryAfterMs = 0L
        return mcpApiKey
    }

    static void initSessionService(ExecutionContextFactory ecf) {
        if (sharedSessionService == null) {
            sharedSessionService = new MoquiSessionService(ecf)
        }
        if (sharedArtifactService == null) {
            sharedArtifactService = new MoquiArtifactService(ecf)
        }
    }

    /**
     * Idempotent lazy init callable from the servlet (no Moqui service context needed).
     * Reads all enabled DB configs first, falls back to env vars, then defaults to HelloTimeAgent.
     */
    static void lazyInit(ExecutionContextFactory ecf) {
        initSessionService(ecf)
        // Close toolsets at the very start of JVM exit — before Moqui drains its worker pool —
        // so a pending McpToolset SSE connect is disposed (fails fast) instead of retry+timeout.
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.runtime.addShutdownHook(new Thread({
                shuttingDown = true
                closeAllToolsets()
            }, 'adk-shutdown'))
        }
        if (!registry.isEmpty()) return

        def cfgList = null
        try {
            def ec = ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try { cfgList = ec.entity.find('moqui.adk.AdkAgentConfig').condition('enabled', 'Y').list() }
            finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception ignored) {}

        // Key to use for the general default agent (env, else borrowed from a config).
        String defaultKey = System.getenv('GOOGLE_API_KEY') ?:
                            System.getenv('GOOGLE_GENAI_API_KEY') ?:
                            System.getenv('GEMINI_API_KEY') ?: ''
        // left null unless a key (and model) is borrowed from a gemini agent below, so a
        // tenant with only an anthropic key does not get a Gemini model name forced on it
        String defaultModel = null

        if (cfgList) {
            // getExecutionContext() returns the CALLER's thread-local EC when one exists
            // (e.g. the scheduled-job EC that triggered lazyInit), so never destroy it here —
            // that logs out the job user and later entity ops fail with "[No User]".
            def ec2 = ecf.getExecutionContext()
            boolean wasDisabled2 = ec2.artifactExecution.disableAuthz()
            try {
                for (def cfg in cfgList) {
                    String resolvedApiKey = cfg.getString('apiKey') ?: ''
                    String provider = cfg.getString('llmProvider') ?: 'gemini'
                    if (!resolvedApiKey) {
                        def lc = ec2.entity.find('growerp.general.LlmConfig')
                            .condition('ownerPartyId', cfg.getString('ownerPartyId'))
                            .condition('llmProvider', provider).one()
                        resolvedApiKey = lc?.getString('apiKey') ?: ''
                    }
                    if (!defaultKey && resolvedApiKey && provider == 'gemini') {
                        defaultKey = resolvedApiKey
                        if (cfg.getString('modelName')) defaultModel = cfg.getString('modelName')
                    }
                    initConfig(cfg.getString('adkAgentConfigId'), cfg.getString('ownerPartyId'),
                            cfg.getString('agentName'), cfg.getString('modelName'),
                            withParameters(cfg.getString('instruction'), cfg.getString('parameters')),
                            resolvedApiKey, provider, cfg.getString('description'))
                }
            } catch (Exception ignored) {
                for (def cfg in cfgList) {
                    if (!defaultKey && cfg.getString('apiKey')) defaultKey = cfg.getString('apiKey')
                    initConfig(cfg.getString('adkAgentConfigId'), cfg.getString('ownerPartyId'),
                            cfg.getString('agentName'), cfg.getString('modelName'),
                            withParameters(cfg.getString('instruction'), cfg.getString('parameters')),
                            cfg.getString('apiKey') ?: '',
                            cfg.getString('llmProvider') ?: 'gemini', cfg.getString('description'))
                }
            } finally {
                if (!wasDisabled2) ec2.artifactExecution.enableAuthz()
            }
        }

        // Always register a general-purpose default agent for INTERACTIVE CHAT, so chat
        // sessions are not served by a specialised/scheduled agent (e.g. the CI Monitor,
        // whose task instruction makes general questions return empty/odd answers).
        ensureInteractiveDefault(ecf, defaultKey, defaultModel)
    }

    /// Register the shared DEFAULT_CONFIG runner used for interactive chat, deriving its key
    /// from (in order) growerp.general.LlmConfig for each supported provider, env vars, then
    /// [seedKey]. No-op when the runner already exists. Called by lazyInit and reloadInteractive.
    private static void ensureInteractiveDefault(ExecutionContextFactory ecf, String seedKey = null,
                                                 String model = null) {
        if (registry.containsKey(DEFAULT_CONFIG)) return
        // Precedence for the shared interactive runner: key saved via System Setup
        // (growerp.general.LlmConfig) → explicit env var → key borrowed from a specialised
        // agent (seedKey). The System Setup key must win over the env var so an admin/tenant
        // can always override a stale or server-wide key without a restart.
        String defaultKey = ''
        String provider = 'gemini'
        try {
            def ec = ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                for (String candidate in SUPPORTED_PROVIDERS) {
                    def lcList = ec.entity.find('growerp.general.LlmConfig')
                            .condition('llmProvider', candidate).list()
                    for (def lc in lcList) {
                        String k = lc.getString('apiKey')
                        if (k) { defaultKey = k; provider = candidate; break }
                    }
                    logger.info("ensureInteractiveDefault: LlmConfig {} rows={}, keyFound={}",
                            candidate, lcList?.size() ?: 0, (defaultKey ? true : false))
                    if (defaultKey) break
                }
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.error("ensureInteractiveDefault: LlmConfig lookup failed: ${e.message}", e)
        }
        for (String candidate in SUPPORTED_PROVIDERS) {
            if (defaultKey) break
            String envKey = envKeyFor(candidate)
            if (envKey) { defaultKey = envKey; provider = candidate }
        }
        if (!defaultKey) defaultKey = seedKey ?: ''
        // the borrowed seed key and its model both come from a gemini agent (see lazyInit)
        String modelId = model ?: defaultModelFor(provider)
        logger.info("ensureInteractiveDefault: registering __default__ provider={} hasKey={} (seedKeyPresent={})",
                provider, (defaultKey ? true : false), (seedKey ? true : false))
        initConfig(DEFAULT_CONFIG, null, null, modelId, '', defaultKey, provider)
    }

    /// Ensure a general per-tenant interactive runner exists for [ownerPartyId] and owns
    /// the tenant's chat route. Built once per owner (configId 'INTERACTIVE_<owner>'); its
    /// McpToolset carries the owner so searchKnowledge can resolve the tenant. No-op when
    /// already registered. Falls back to the global default if no key can be found.
    static synchronized void ensureInteractiveForTenant(String ownerPartyId, boolean strictTenant = false) {
        if (!ownerPartyId || sharedSessionService == null) return
        String cid = (strictTenant ? 'HTTP_INTERACTIVE_' : 'INTERACTIVE_') + ownerPartyId
        if (registry.containsKey(cid)) return
        // A coordinator (or already-built interactive agent) may already own this tenant's chat
        // route — don't shadow it with the generic interactive default.
        if (!strictTenant && tenantRegistry.containsKey(ownerPartyId)) return
        Map llm = resolveTenantLlm(ownerPartyId, strictTenant)
        if (!llm) {
            logger.warn("ensureInteractiveForTenant: no LLM key for owner=${ownerPartyId}; " +
                    "interactive chat will use the global default (no tenant knowledge access)")
            return
        }
        // agentName=null → builds the general 'growerp-agent'; ownerPartyId set + unnamed →
        // claims tenantRegistry[owner] and its McpToolset carries adk_owner_party_id.
        initConfig(cid, ownerPartyId, null,
                interactiveModelFor(ownerPartyId, llm.provider as String, strictTenant), '',
                llm.key as String, llm.provider as String)
        logger.info("Registered per-tenant interactive agent configId='${cid}' (owner=${ownerPartyId})")
    }

    /** HTTP chat has no persisted AgentConfig model, so honour only its own tenant's
     *  System Setup choice. Never send a model to a different provider's key. Internal
     *  and named agents keep their existing model-selection rules. */
    private static String interactiveModelFor(String ownerPartyId, String provider, boolean strictTenant) {
        if (strictTenant && ownerPartyId && sharedSessionService != null) {
            try {
                def ec = sharedSessionService.ecf.getExecutionContext()
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    def settings = ec.entity.find('growerp.general.SystemSettings')
                            .condition('ownerPartyId', ownerPartyId).one()
                    String model = (settings?.aiModelName as String)?.trim()
                    if (model) {
                        String modelProvider = (settings.aiProvider as String)?.trim()
                        if (!modelProvider) {
                            modelProvider = model.startsWith('claude') ? 'anthropic' :
                                    ((model.startsWith('gpt') || model ==~ /^o\d.*/) ? 'openai' : 'gemini')
                        }
                        if (modelProvider == provider) return model
                    }
                } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            } catch (Exception e) {
                logger.warn("interactiveModelFor: tenant model lookup failed for owner=${ownerPartyId}")
            }
        }
        return defaultModelFor(provider)
    }

    /** Model-only settings changes invalidate just this tenant's HTTP runner. The next
     *  owned session lookup rebuilds it from committed settings and the same-owner key. */
    static synchronized void invalidateHttpInteractive(String ownerPartyId) {
        if (!ownerPartyId) return
        String cid = 'HTTP_INTERACTIVE_' + ownerPartyId
        registry.remove(cid)
        agentRegistry.remove(cid)
        tenantRegistry.entrySet().removeIf { it.value == cid }
    }

    /** Do not let another request rebuild this runner from uncommitted settings. */
    static void invalidateHttpInteractiveAfterCommit(org.moqui.context.TransactionFacade transaction,
                                                     String ownerPartyId) {
        if (!ownerPartyId) return
        if (transaction != null && transaction.isTransactionInPlace()) {
            transaction.registerSynchronization([
                    beforeCompletion: { },
                    afterCompletion: { int status ->
                        if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                            invalidateHttpInteractive(ownerPartyId)
                        }
                    }
            ] as jakarta.transaction.Synchronization)
        } else {
            invalidateHttpInteractive(ownerPartyId)
        }
    }

    /// Resolve an API key for [ownerPartyId] and [provider]: this owner's LlmConfig → any
    /// LlmConfig for that provider → a key borrowed from an AdkAgentConfig → env vars.
    /// Returns '' when none found.
    private static String resolveTenantKey(String ownerPartyId, String provider = 'gemini', boolean strictTenant = false) {
        String key = ''
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def lc = ec.entity.find('growerp.general.LlmConfig')
                        .condition('ownerPartyId', ownerPartyId)
                        .condition('llmProvider', provider).one()
                key = lc?.getString('apiKey') ?: ''
                if (!key && !strictTenant) {
                    for (def row in ec.entity.find('growerp.general.LlmConfig')
                            .condition('llmProvider', provider).list()) {
                        String k = row.getString('apiKey')
                        if (k) { key = k; break }
                    }
                }
                // Fall back to a key stored directly on an AdkAgentConfig (this owner first,
                // then any agent on that provider) — mirrors lazyInit's key-borrowing so the
                // interactive agent works even when only a scheduled agent holds the key.
                if (!key) {
                    def ac = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('ownerPartyId', ownerPartyId)
                            .condition('llmProvider', provider).list()
                    for (def row in ac) { String k = row.getString('apiKey'); if (k) { key = k; break } }
                }
                if (!key && !strictTenant) {
                    for (def row in ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('llmProvider', provider).list()) {
                        String k = row.getString('apiKey')
                        if (k) { key = k; break }
                    }
                }
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("resolveTenantKey(${ownerPartyId}, ${provider}) failed: ${e.message}")
        }
        if (!key && !strictTenant) key = envKeyFor(provider)
        return key
    }

    /// The provider and key to serve [ownerPartyId] with: the supported providers in order,
    /// first one that has a key anywhere. Returns null when nothing is configured.
    private static Map resolveTenantLlm(String ownerPartyId, boolean strictTenant = false) {
        for (String provider in SUPPORTED_PROVIDERS) {
            String key = resolveTenantKey(ownerPartyId, provider, strictTenant)
            if (key) return [provider: provider, key: key]
        }
        return null
    }

    /// Drop the shared interactive/default runner and re-create it so a key change
    /// (e.g. saved in System Setup) takes effect on the next chat without a restart.
    /// Scheduled/specialised agent runners and the MCP toolset are left intact.
    static synchronized void reloadInteractive(ExecutionContextFactory ecf) {
        invalidateInteractiveRunners()
        currentConfig = [:]
        ensureInteractiveDefault(ecf)
    }

    private static void invalidateInteractiveRunners() {
        registry.remove(DEFAULT_CONFIG)
        agentRegistry.remove(DEFAULT_CONFIG)
        // Also drop per-tenant interactive runners (INTERACTIVE_<owner>) so a key
        // change OR removal in System Setup takes effect for tenant chat. They are
        // rebuilt on the next createSession via ensureInteractiveForTenant — which
        // re-resolves the current key, so a deleted key correctly stops the chat.
        def interactiveCids = registry.keySet().findAll {
            it.startsWith('INTERACTIVE_') || it.startsWith('HTTP_INTERACTIVE_')
        }
        for (String cid in interactiveCids) {
            registry.remove(cid)
            agentRegistry.remove(cid)
            tenantRegistry.entrySet().removeIf { it.value == cid }
        }
    }

    /// Rebuild a single named config (e.g. a coordinator after its team changed) so the
    /// running registry reflects the latest DB state without a restart.
    static synchronized void reloadConfig(String configId) {
        if (!configId) return
        registry.remove(configId)
        agentRegistry.remove(configId)
        ensureAgentBuilt(configId)
    }

    /// Drop a single config from the running registry and close only its toolsets, leaving
    /// the rest of the ADK runtime (every other agent and tenant) alive. Used when a config
    /// is deleted: destroy() would shut the whole JVM's ADK down until a restart.
    static synchronized void unloadConfig(String configId) {
        if (!configId) return
        registry.remove(configId)
        agentRegistry.remove(configId)
        tenantRegistry.entrySet().removeIf { it.value == configId }
        def ts = configMcpToolsets.remove(configId)
        if (ts != null) {
            try { ts.close() } catch (Exception e) {
                logger.warn("Error closing McpToolset for config ${configId}: ${e.message}")
            }
        }
        def external = configExternalToolsets.remove(configId)
        if (external) external.each { t ->
            try { t?.close() } catch (Exception e) {
                logger.warn("Error closing external McpToolset for config ${configId}: ${e.message}")
            }
        }
    }

    static boolean isInitialized() { !registry.isEmpty() }

    static List<String> listAgents() {
        registry.values().collect { it.agent().name() }.unique() ?: ['hello-time-agent']
    }

    // ── Session management ────────────────────────────────────────────────────

    static Map createSession(String userId, Map<String, Object> initialState = [:], boolean requireTenant = false) {
        String tenantId = initialState?.get('tenantId') as String
        // Interactive chat must run as a general per-tenant agent (NOT a scheduled/named
        // agent like the CI Monitor) so it carries the tenant owner for searchKnowledge.
        if (tenantId && tenantId != 'DEFAULT') ensureInteractiveForTenant(tenantId, requireTenant)
        // HTTP sessions must never borrow another company's runner/tools.
        // Legacy scheduled/internal callers retain their explicit default behavior.
        String configId = requireTenant ? 'HTTP_INTERACTIVE_' + tenantId : resolveConfigId(tenantId)
        Runner runner = requireTenant ? (configId ? registry[configId] : null) :
                (registry[configId] ?: registry.values().find())
        if (!runner) throw new IllegalStateException('ADK not initialized — add an LLM API key in System Setup')

        def session = runner.sessionService()
                .createSession(APP_NAME, userId, initialState ?: [:], null)
                .blockingGet()

        sessionOwn[session.id()] = configId
        persistSessionConfigId(session.id(), configId)

        AdkSessionHolder.sessions[session.id()] = []
        AdkSessionHolder.logEvent(session.id(), 'session_created', 'Session created', [sessionId: session.id()])

        [id           : session.id(),
         appName      : APP_NAME,
         userId       : session.userId(),
         state        : session.state() ?: [:],
         events       : [],
         lastUpdateTime: System.currentTimeMillis()]
    }

    static Map getSession(String userId, String sessionId, String ownerPartyId = null) {
        def runner = runnerForSession(sessionId, ownerPartyId)
        def sessionOpt = runner.sessionService()
                .getSession(APP_NAME, userId, sessionId, Optional.empty())
                .blockingGet()
        if (!sessionOpt) return null
        [id           : sessionOpt.id(),
         appName      : APP_NAME,
         userId       : sessionOpt.userId(),
         state        : sessionOpt.state() ?: [:],
         events       : [],
         lastUpdateTime: System.currentTimeMillis()]
    }

    static List<Map> listSessions(String userId) {
        // Use first available runner (sessions are shared via MoquiSessionService)
        def runner = registry.values().find()
        def response = runner?.sessionService()?.listSessions(APP_NAME, userId)?.blockingGet()
        response?.sessions()?.collect { s -> [id: s.id(), appName: APP_NAME, userId: s.userId()] } ?: []
    }

    static void deleteSession(String userId, String sessionId, String ownerPartyId = null) {
        def runner = runnerForSession(sessionId, ownerPartyId)
        runner.sessionService().deleteSession(APP_NAME, userId, sessionId).blockingAwait()
        sessionOwn.remove(sessionId)
        turnCounts.remove(sessionId)
    }

    // ── Agent execution ───────────────────────────────────────────────────────

    /** Per-run LLM call cap: the agent's maxLlmCalls when set, else 10 (cost guard). */
    static RunConfig defaultRunConfig(String configId = null) {
        RunConfig.builder().setMaxLlmCalls(lookupMaxLlmCalls(configId) ?: 10).build()
    }

    /**
     * Token allowance gate before an agent run: the reason the run is refused, or null to go
     * ahead. Owner comes from the agent config, else [ownerPartyId] (one-off runs pass their
     * tenantId), else the session user's company. Uses the caller's thread EC, never destroyed
     * here. Fails open on lookup errors so a metering hiccup never takes chat down.
     */
    static String allowanceBlock(String configId, String sessionId, String ownerPartyId = null) {
        if (sharedSessionService == null) return null
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                String apiKey = null, provider = 'gemini'
                if (configId && configId != DEFAULT_CONFIG) {
                    def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('adkAgentConfigId', configId).one()
                    if (cfg?.ownerPartyId) ownerPartyId = cfg.ownerPartyId as String
                    apiKey = cfg?.apiKey as String
                    provider = (cfg?.llmProvider ?: 'gemini') as String
                }
                if (!ownerPartyId || ownerPartyId == 'DEFAULT') ownerPartyId = ownerForSession(ec, sessionId)
                if (!ownerPartyId) return null
                def out = ec.service.sync().name('AdkGovernanceServices.check#TokenAllowance')
                        .parameters([ownerPartyId: ownerPartyId, apiKey: apiKey, llmProvider: provider]).call()
                return out?.allowed == false ? (out.message as String) : null
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("allowanceBlock(${configId}/${sessionId}) failed: ${e.message}")
            return null
        }
    }

    /** Company of the user who owns a persisted session; null for in-memory/unknown sessions. */
    private static String ownerForSession(def ec, String sessionId) {
        if (!sessionId) return null
        def sess = ec.entity.find('moqui.adk.AdkSession').condition('adkSessionId', sessionId).one()
        if (!sess?.userId) return null
        def ua = ec.entity.find('moqui.security.UserAccount').condition('userId', sess.userId).one()
        if (!ua?.partyId) return null
        return ec.entity.find('mantle.party.Party').condition('partyId', ua.partyId).one()?.ownerPartyId as String
    }

    /** The single agent reply event returned instead of running when the allowance is used up. */
    private static Map blockedEvent(String message) {
        [id: 'allowance-' + System.currentTimeMillis(), author: 'growerp-agent', partial: false,
         content: [role: 'model', parts: [[text: message]]]]
    }

    /**
     * After a completed interactive turn, fold the session into the rolling per-(owner,user)
     * AdkMemory every N turns. Runs async on a daemon thread (the summary needs an LLM call)
     * so it never delays the chat response; failures are logged and swallowed.
     */
    static void maybeSummarize(String sessionId) {
        if (!sessionId || sharedSessionService == null) return
        int n = turnCounts.merge(sessionId, 1, { a, b -> a + b })
        if (n % SUMMARIZE_EVERY_N_TURNS != 0) return
        def ecf = sharedSessionService.ecf
        Thread t = new Thread({
            def ec = ecf.getExecutionContext()
            try {
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    ec.service.sync().name('AdkKnowledgeServices.summarize#AdkSession')
                            .parameters([adkSessionId: sessionId]).call()
                } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            } catch (Exception e) {
                logger.warn("maybeSummarize(${sessionId}) failed: ${e.message}")
            } finally { ec.destroy() }
        }, 'adk-summarize')
        t.setDaemon(true)
        t.start()
    }

    /**
     * Record an explicit delegation row in AdkActionLog for each specialist (other than the
     * session's coordinator) that authored an event this turn — so Agent Actions shows
     * "Coordinator → Specialist" at a glance, not just inferred from per-member configIds.
     * Async/best-effort. No-op for single-agent sessions (only the agent itself authors).
     */
    static void logDelegations(String sessionId, Set<String> authors) {
        if (!sessionId || !authors || authors.isEmpty() || sharedSessionService == null) return
        String coordId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId)
        if (!coordId) return
        def ecf = sharedSessionService.ecf
        Thread t = new Thread({
            def ec = ecf.getExecutionContext()
            try {
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    def coord = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('adkAgentConfigId', coordId).one()
                    if (!coord) return
                    String owner = coord.ownerPartyId as String
                    String coordName = coord.agentName as String
                    for (String a in authors) {
                        if (!a || a == coordName || a == 'growerp-agent') continue
                        // map the responding agent name → a team member config of this owner
                        def member = ec.entity.find('moqui.adk.AdkAgentConfig')
                                .condition('ownerPartyId', owner).condition('agentName', a).one()
                        if (!member) continue
                        ec.service.sync().name('create#moqui.adk.AdkActionLog').parameters([
                                ownerPartyId: owner, configId: member.adkAgentConfigId,
                                parentConfigId: coordId, adkSessionId: sessionId,
                                toolName: a, serviceName: 'delegate', verbClass: 'delegate',
                                decision: 'delegated', reason: "${coordName} → ${a}",
                                actionTime: ec.user.nowTimestamp]).call()
                    }
                } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            } catch (Exception e) {
                logger.warn("logDelegations(${sessionId}) failed: ${e.message}")
            } finally { ec.destroy() }
        }, 'adk-delegation-log')
        t.setDaemon(true)
        t.start()
    }

    /**
     * Extract token counts summed across all events that carry usageMetadata.
     * Returns [tokensIn, tokensOut, tokensTotal].
     */
    static long[] extractTokensFromEvents(List<Map> events) {
        long tokensIn = 0, tokensOut = 0, total = 0
        for (Map ev in events) {
            def um = ev.usageMetadata
            if (um instanceof Map) {
                tokensIn  += (um.promptTokenCount     ?: 0) as long
                tokensOut += (um.candidatesTokenCount ?: 0) as long
                total     += (um.totalTokenCount       ?: 0) as long
            }
        }
        if (total == 0 && (tokensIn > 0 || tokensOut > 0)) total = tokensIn + tokensOut
        [tokensIn, tokensOut, total] as long[]
    }

    /**
     * Record a top-level chat interaction in AdkActionLog with real token counts.
     * Must be called after the run completes so events are available.
     */
    static void logChatTurn(String sessionId, String text, List<Map> events) {
        if (!sessionId || sharedSessionService == null) return
        String coordId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId) ?: DEFAULT_CONFIG
        logAgentRun(coordId, sessionId, 'chat', 'chat', 'User Chat Interaction', text, events)
    }

    /**
     * Write one AdkActionLog row carrying the token counts of a completed agent run.
     * Used for interactive chat turns and for scheduled/one-off runs, which have no
     * persisted session to look a config id up from.
     */
    static void logAgentRun(String configId, String sessionId, String serviceName,
                            String verbClass, String reason, String text, List<Map> events,
                            String ownerPartyId = null) {
        if (!configId || sharedSessionService == null) return
        long[] tokens = extractTokensFromEvents(events)
        def ecf = sharedSessionService.ecf
        Thread t = new Thread({
            def ec = ecf.getExecutionContext()
            try {
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    def coord = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('adkAgentConfigId', configId).one()
                    // the default config has no row: bill the given tenant or the session user's
                    String owner = (coord?.ownerPartyId ?:
                            (ownerPartyId && ownerPartyId != 'DEFAULT' ? ownerPartyId : null) ?:
                            ownerForSession(ec, sessionId)) as String
                    if (!owner) {
                        logger.warn("logAgentRun(${configId}/${sessionId}): no owner, ${tokens[2]} tokens unmetered")
                        return
                    }
                    ec.service.sync().name('create#moqui.adk.AdkActionLog').parameters([
                            ownerPartyId: owner, configId: coord ? configId : null,
                            adkSessionId: sessionId,
                            serviceName: serviceName, verbClass: verbClass,
                            decision: 'allowed', reason: reason,
                            argsJson: "{\"text\": \"${(text ?: '').take(200).replace('"', '\\"')}\"}",
                            tokensIn: tokens[0], tokensOut: tokens[1], tokensTotal: tokens[2],
                            actionTime: ec.user.nowTimestamp]).call()
                } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            } catch (Exception e) {
                logger.warn("logAgentRun(${configId}/${sessionId}) failed: ${e.message}")
            } finally { ec.destroy() }
        }, 'adk-chat-log')
        t.setDaemon(true)
        t.start()
    }

    static List<Map> runAgent(String userId, String sessionId, String text, String ownerPartyId = null) {
        if (shuttingDown) return []
        Runner runner = runnerForSession(sessionId, ownerPartyId)
        String runConfigId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId)
        String blocked = allowanceBlock(runConfigId, sessionId)
        if (blocked) return [blockedEvent(blocked)]
        Content userContent = buildUserContent(text)
        List<Map> events = []
        Set<String> delegateNames = new HashSet<>()
        Throwable[] err  = [null]
        runner.runAsync(userId, sessionId, userContent, defaultRunConfig(runConfigId))
            .blockingSubscribe(
                { Event e -> collectDelegateNames(e, delegateNames); events << eventToMap(e) },
                { Throwable t -> err[0] = t; logger.error("ADK runAgent error (session={}): {}", sessionId, t.message, t) }
            )
        if (err[0]) {
            // tokens of the LLM calls before the failure were still spent
            logChatTurn(sessionId, text, events)
            throw err[0]
        }
        logChatTurn(sessionId, text, events)
        logDelegations(sessionId, delegateNames)
        maybeSummarize(sessionId)
        events
    }

    /** Names of specialists a coordinator delegated to this turn: event authors (workflow
     *  sub-agents) + function-call names (router AgentTools — the inner runner doesn't surface
     *  the specialist as an author, but the coordinator's functionCall is named after it). */
    private static void collectDelegateNames(Event e, Set<String> acc) {
        try {
            if (e.author()) acc.add(e.author())
            Optional<Content> co = e.content()
            if (co?.isPresent() && co.get().parts()?.isPresent()) {
                for (Part p in co.get().parts().get()) {
                    def fc = p.functionCall()
                    if (fc?.isPresent() && fc.get().name()?.isPresent()) acc.add(fc.get().name().get())
                }
            }
        } catch (Exception ignore) {}
    }

    /**
     * Run a one-off agent turn for the scheduler.
     * Uses a fresh InMemorySessionService to avoid the DB transaction isolation
     * issue: createSession writes to DB within an open (uncommitted) service
     * transaction; a subsequent runAsync on an IO thread cannot see it via
     * MoquiSessionService.getSession. In-memory sessions need no DB round-trip.
     */
    static List<Map> runOneOff(String configId, String userId, String text,
                               Map<String, Object> initialState = [:],
                               LlmAgent agentOverride = null, Closure eventSink = null) {
        if (shuttingDown) return []
        String cid = configId ?: DEFAULT_CONFIG
        LlmAgent agent = agentOverride ?: agentRegistry[cid] ?: agentRegistry.values().find()
        if (!agent) throw new IllegalStateException('ADK not initialized — add API key in ADK → Configuration')
        String tenantId = initialState?.tenantId as String
        String blocked = allowanceBlock(cid, null, tenantId)
        if (blocked) return [blockedEvent(blocked)]

        // The agent instruction embeds CONTEXT_PREAMBLE with {userId}/{username}/... placeholders
        // that ADK resolves from session state. Seed state with these keys so injectSessionState
        // does not throw "Context variable not found".
        def state = new java.util.concurrent.ConcurrentHashMap<String, Object>(initialState ?: [:])
        // {screenCatalog} and {memory} appear in the instruction preamble; scheduled/one-off
        // runs have no Flutter client or prior conversation history, so default them to avoid
        // "Context variable not found".
        state.putIfAbsent('screenCatalog', '[]')
        state.putIfAbsent('memory', '')

        def inMemSvc = new InMemorySessionService()
        def session  = inMemSvc.createSession(APP_NAME, userId, state, null).blockingGet()

        Runner oneOff = Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .sessionService(inMemSvc)
                .artifactService(sharedArtifactService ?: new com.google.adk.artifacts.InMemoryArtifactService())
                .build()

        Content userContent = buildUserContent(text)
        List<Map> events = []
        Throwable[] err  = [null]

        oneOff.runAsync(userId, session.id(), userContent, defaultRunConfig(cid))
              .blockingSubscribe(
                  { Event e -> events << eventToMap(e); if (eventSink) eventSink(e) },
                  { Throwable t -> err[0] = t; logger.error("ADK runOneOff error (config={}): {}", cid, t.message, t) }
              )

        // scheduled and one-off runs have their own in-memory session: log against the config,
        // also when the run failed part way (earlier LLM calls were still spent)
        logAgentRun(cid, session.id() as String, 'agentRun', 'ai',
                'Scheduled or one-off agent run', text, events, tenantId)
        if (err[0]) throw err[0]
        events
    }

    // ── Agent test runs (agent dialog → Test) ────────────────────────────────

    /** Test runs by id. In memory only: a result is read within minutes, never after a restart. */
    private static final Map<String, Map> testRuns = new ConcurrentHashMap<>()

    /** Start a test run of one agent on a background thread; poll {@link #getTestRun}.
     *  dryRun: writes reach the governance gate but are not executed (adk_dry_run MCP header),
     *  and the in-process write tools (email, GitHub, Substack) and external MCP servers are
     *  left out, since those can not be simulated. */
    static String startTestRun(String configId, String prompt, boolean dryRun, Map initialState) {
        long now = System.currentTimeMillis()
        testRuns.values().removeIf { (it.startedAt as long) < now - 3600000L && it.status != 'running' }
        String testRunId = UUID.randomUUID().toString()
        Map run = Collections.synchronizedMap([testRunId: testRunId, configId: configId, status: 'running',
                dryRun: dryRun, startedAt: now, llmCalls: 0, toolCalls: Collections.synchronizedList([])])
        testRuns[testRunId] = run
        Thread.start("adk-test-${configId}") {
            try { executeTestRun(run, prompt, dryRun, initialState) }
            finally { try { sharedSessionService?.ecf?.getExecutionContext()?.destroy() } catch (ignore) {} }
        }
        testRunId
    }

    static Map getTestRun(String testRunId) { testRunId ? testRuns[testRunId] : null }

    /** Keep a finished test run made without an LLM (program mode) so get#AdkAgentTest finds it. */
    static String putTestRun(String configId, Map result) {
        String testRunId = UUID.randomUUID().toString()
        testRuns[testRunId] = Collections.synchronizedMap(new LinkedHashMap(result) +
                [testRunId: testRunId, configId: configId, startedAt: System.currentTimeMillis()])
        testRunId
    }

    private static void executeTestRun(Map run, String prompt, boolean dryRun, Map initialState) {
        String configId = run.configId as String
        def testToolset = null
        try {
            if (sharedSessionService == null) throw new IllegalStateException('ADK not initialized — add an API key in AI Settings')
            def ec = sharedSessionService.ecf.getExecutionContext()
            def cfg
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try { cfg = ec.entity.find('moqui.adk.AdkAgentConfig').condition('adkAgentConfigId', configId).one() }
            finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            if (!cfg) throw new IllegalArgumentException("Agent ${configId} not found")
            if (cfg.agentRole in ['coordinator', 'workflow'])
                throw new IllegalArgumentException('Test run works on a single agent: test each team member instead.')
            String provider = cfg.llmProvider ?: 'gemini'
            if (!SUPPORTED_PROVIDERS.contains(provider)) throw new IllegalArgumentException("Provider ${provider} can not run agents")
            String key = (cfg.apiKey as String) ?: resolveTenantKey(cfg.ownerPartyId as String, provider)
            if (!key && !envKeyFor(provider)) throw new IllegalStateException("No ${provider} API key")
            def modelArg = buildModel(provider, (cfg.modelName as String) ?: defaultModelFor(provider), key)
            String toolMode = (cfg.toolMode ?: 'full') as String
            boolean webSearch = cfg.webSearch == 'Y' && provider == 'gemini'
            List tools = assembleFunctionTools(!dryRun && toolMode != 'readOnly', webSearch, modelArg)
            testToolset = buildMcpToolset(configId, cfg.ownerPartyId as String, toolMode, dryRun)
            if (testToolset) tools.add(testToolset)
            if (!dryRun) loadExternalMcpToolsets(configId, cfg.ownerPartyId as String).each { tools.add(it) }
            String agentName = sanitizeAgentName((cfg.agentName ?: configId) as String)
            LlmAgent agent = LlmAgent.builder()
                    .name(agentName)
                    .description((cfg.description ?: cfg.agentName ?: 'NASQO agent') as String)
                    .model(modelArg)
                    .instruction(CONTEXT_PREAMBLE + withParameters(cfg.instruction as String, cfg.parameters as String))
                    .tools(tools)
                    .build()
            run.maxLlmCalls = (cfg.maxLlmCalls as Integer) ?: 10
            List<Map> events = runOneOff(configId, 'agent-test', prompt, initialState, agent,
                    { Event e -> collectTestEvent(run, e) })
            run.tokensTotal = extractTokensFromEvents(events)[2]
            run.response = events.findAll { !it.partial && it.author != 'agent-test' }
                    .collect { (it.content?.parts ?: []).collect { it.text ?: '' }.join('') }
                    .findAll { it }.join('\n\n')
            run.status = 'done'
        } catch (Throwable t) {
            run.status = 'failed'
            run.error = t.message ?: t.class.simpleName
            run.hint = testRunHint(run.error as String)
        } finally {
            run.durationMs = System.currentTimeMillis() - (run.startedAt as long)
            if (testToolset) try { testToolset.close() } catch (ignore) {}
        }
    }

    /** Add one ADK event to a test run: model responses count as LLM calls, function calls
     *  and their responses become the tool-call trace. */
    private static void collectTestEvent(Map run, Event e) {
        if (!e.content().isPresent()) return
        Content c = e.content().get()
        if (c.role().orElse('') == 'model' && !e.partial().orElse(false)) run.llmCalls = (run.llmCalls as int) + 1
        c.parts().orElse([]).each { Part p ->
            p.functionCall().ifPresent { fc ->
                Map args = fc.args().orElse([:]) as Map
                run.toolCalls << [callId: fc.id().orElse(null), tool: fc.name().orElse(''), service: args.serviceName,
                        args: groovy.json.JsonOutput.toJson(args.serviceName ? (args.parameters ?: [:]) : args).take(600)]
            }
            p.functionResponse().ifPresent { fr ->
                // parallel calls answer out of order: pair by call id, else the oldest open call
                String callId = fr.id().orElse(null)
                String tool = fr.name().orElse('')
                Map call = (callId ? run.toolCalls.find { it.callId == callId } : null) ?:
                        run.toolCalls.find { it.tool == tool && it.result == null }
                if (call == null) return
                String text = groovy.json.JsonOutput.toJson(fr.response().orElse([:]))
                call.result = text.take(800)
                call.decision = text.contains('DRY RUN') ? 'simulated' :
                        text.contains('human approval') ? 'approval' :
                        (text.contains('"isError":true') || text.contains('not in this agent') ||
                                text.contains('denied') || text.contains('Unknown service')) ? 'blocked' : 'ok'
            }
        }
    }

    /**
     * The instruction plus its input parameters (AdkAgentConfig.parameters, a JSON list of
     * [name, label, value, fixed]). Rendered as plain text, not as ADK state variables: an unset
     * variable fails the run. A value is the default for runs whose message names none, so a
     * scheduled run gets it; a fixed value also overrides the message.
     */
    static String withParameters(String instruction, String parametersJson) {
        String text = instruction ?: ''
        if (!parametersJson?.trim()) return text
        List params
        try { params = new groovy.json.JsonSlurper().parseText(parametersJson) as List }
        catch (Exception e) { logger.warn("Agent parameters are not valid JSON: ${e.message}"); return text }
        // braces would be read as ADK session-state variables
        Closure clean = { Object v -> ((v ?: '') as String).replace('{', '[').replace('}', ']').trim() }
        List<String> lines = params.findAll { it instanceof Map && it.name }.collect { Map p ->
            String label = clean(p.label)
            "- ${clean(p.name)}${label ? ' (' + label + ')' : ''}: " + (p.fixed ?
                    "${clean(p.value)} (fixed: use this value, ignore any other value in the message)" :
                    (clean(p.value) ?: '(no default: take it from the message)'))
        } as List<String>
        if (!lines) return text
        text + '\n\nINPUT PARAMETERS (use the value given in the message; otherwise the value below):\n' +
                lines.join('\n')
    }

    /** A plain-language fix for the run errors agent builders hit. */
    private static String testRunHint(String error) {
        if (!error) return null
        if (error.contains('Context variable not found'))
            return 'The instruction contains {name}, which is read as a variable. Write [name] instead.'
        if (error.contains('llm calls limit'))
            return "The run used all its AI calls. Raise 'Max AI calls per run'."
        if (error.contains('allowance') || error.contains('API key'))
            return 'Add your own API key in System Setup → AI Settings.'
        return null
    }

    static void runAgentSse(String userId, String sessionId, String text,
                            Closure eventCallback, Closure doneCallback,
                            String ownerPartyId = null, boolean blockUntilComplete = false) {
        if (shuttingDown) { doneCallback(null); return }
        // Resolve the exact caller-owned runner before any provider allowance call.
        Runner runner = runnerForSession(sessionId, ownerPartyId)
        String runConfigId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId)
        String blocked = allowanceBlock(runConfigId, sessionId)
        if (blocked) { eventCallback(blockedEvent(blocked)); doneCallback(null); return }
        Content userContent = buildUserContent(text)
        Set<String> delegateNames = java.util.concurrent.ConcurrentHashMap.newKeySet()
        List<Map> collectedEvents = Collections.synchronizedList(new ArrayList<>())
        def events = runner.runAsync(userId, sessionId, userContent, defaultRunConfig(runConfigId))
        Closure onEvent = { Event e ->
            def em = eventToMap(e)
            collectDelegateNames(e, delegateNames)
            collectedEvents.add(em)
            eventCallback(em)
        }
        Closure onError = { Throwable t ->
            logChatTurn(sessionId, text, collectedEvents)
            doneCallback(t)
        }
        Closure onComplete = {
            logChatTurn(sessionId, text, collectedEvents)
            logDelegations(sessionId, delegateNames)
            maybeSummarize(sessionId)
            doneCallback(null)
        }
        // A servlet response must remain open through the final SSE event.
        // Background/internal consumers retain the original nonblocking behavior.
        if (blockUntilComplete) events.blockingSubscribe(onEvent, onError, onComplete)
        else events.subscribe(onEvent, onError, onComplete)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static String resolveConfigId(String ownerPartyId) {
        if (ownerPartyId && tenantRegistry[ownerPartyId]) return tenantRegistry[ownerPartyId]
        // Fall back to the only/default registered config
        return registry.containsKey(DEFAULT_CONFIG) ? DEFAULT_CONFIG : (registry.keySet().find() ?: DEFAULT_CONFIG)
    }

    private static Runner runnerForSession(String sessionId, String ownerPartyId = null) {
        String configId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId)
        if (ownerPartyId) {
            String expected = 'HTTP_INTERACTIVE_' + ownerPartyId
            if (configId != expected) {
                throw new IllegalStateException('ADK session is unavailable — reopen the assistant')
            }
            // Rebuild this tenant's dedicated HTTP runner after a process restart.
            ensureInteractiveForTenant(ownerPartyId, true)
            Runner ownedRunner = registry[expected]
            if (!ownedRunner) {
                throw new IllegalStateException('ADK not initialized — add an LLM API key in System Setup')
            }
            return ownedRunner
        }
        configId = configId ?: DEFAULT_CONFIG
        if (configId && configId != DEFAULT_CONFIG) sessionOwn[sessionId] = configId  // cache it
        return registry[configId] ?: registry.values().find() ?: { throw new IllegalStateException('ADK not initialized — add an LLM API key in System Setup') }()
    }

    private static String lookupConfigIdFromDb(String sessionId) {
        if (!sharedSessionService) return null
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def sv = ec.entity.find('moqui.adk.AdkSession').condition('adkSessionId', sessionId).one()
                return sv?.configId as String
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception ignored) { return null }
    }

    private static void persistSessionConfigId(String sessionId, String configId) {
        if (!sharedSessionService) return
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def sv = ec.entity.find('moqui.adk.AdkSession').condition('adkSessionId', sessionId).one()
                if (sv && sv.configId != configId) { sv.configId = configId; sv.update() }
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception ignored) {}
    }

    private static Content buildUserContent(String text) {
        Content.builder().role('user').parts([Part.fromText(text)]).build()
    }

    static Map eventToMap(Event e) {
        Map m = [id: e.id(), invocationId: e.invocationId(), author: e.author()]

        Optional<Content> contentOpt = e.content()
        if (contentOpt.isPresent()) {
            Content c = contentOpt.get()
            // Installed clients retain one text part per event. Join visible answer parts
            // without inserting separators, and never send model thought text to the UI.
            // This changes only the wire projection; the SDK event/session stays unchanged.
            List<String> visibleTextParts = c.parts().orElse([]).findAll { Part p ->
                !p.thought().orElse(false) && p.text().isPresent()
            }.collect { Part p -> p.text().get() }
            m.content = [
                role : c.role().isPresent() ? c.role().get() : '',
                parts: visibleTextParts.isEmpty() ? [] : [[text: visibleTextParts.join('')]]
            ]
        }

        Optional<Boolean> partialOpt = e.partial()
        if (partialOpt.isPresent()) m.partial = partialOpt.get()

        // Capture token usage metadata when present
        try {
            def umOpt = e.usageMetadata()
            if (umOpt?.isPresent()) {
                def um = umOpt.get()
                m.usageMetadata = [
                    promptTokenCount    : um.promptTokenCount()?.orElse(null),
                    candidatesTokenCount: um.candidatesTokenCount()?.orElse(null),
                    totalTokenCount     : um.totalTokenCount()?.orElse(null),
                ]
            }
        } catch (Exception ignore) {}

        m
    }

    // The only write-capable tool the internal MCP server exposes (runs any growerp.* service).
    // govern#AgentAction still blocks the call server-side either way; this just keeps it out
    // of a read-only agent's tool list so the model isn't offered a tool it can't use.
    private static final String MCP_WRITE_TOOL_NAME = 'moqui_execute_service'

    // Fixed tool names the internal moqui MCP server exposes (McpServices.xml) — used only to
    // seed the cross-server collision log in CollisionLoggingToolset below (see loadExternalMcpToolsets
    // callers). Not a security boundary: govern#AgentAction enforces scope server-side regardless
    // of which client-visible tool name triggered the call.
    private static final List<String> INTERNAL_MCP_TOOL_NAMES = [
        'moqui_prompts_list', 'moqui_prompts_get', 'moqui_search_services',
        'moqui_get_service_details', MCP_WRITE_TOOL_NAME, 'moqui_rest_call',
        'searchKnowledge', 'okf_index', 'okf_load_concept', 'okf_follow', 'moqui_get_help'
    ].asImmutable()

    /** Build a per-agent MCP toolset whose SSE headers carry `adk_config_id` (and the
     *  tenant `adk_owner_party_id`) so the governance gate / searchKnowledge on the MCP
     *  server can resolve the calling agent and its tenant. `toolMode` 'readOnly' drops
     *  {@link #MCP_WRITE_TOOL_NAME} from the exposed tool list. */
    private static com.google.adk.tools.mcp.McpToolset buildMcpToolset(String configId, String ownerPartyId = null, String toolMode = null,
                                                                    boolean dryRun = false) {
        // HTTP sessions must not inherit a shared administrative MCP identity. A future
        // caller-scoped adapter must enforce both tenant reads and per-user authorization.
        if (shuttingDown || configId?.startsWith('HTTP_INTERACTIVE_')) return null
        String apiKey = resolveMcpApiKey(sharedSessionService?.ecf)
        if (!apiKey) return null
        Map<String, String> sseHeaders = [
                'Accept': 'application/json, text/event-stream',
                'api_key': apiKey
        ]
        if (configId && configId != DEFAULT_CONFIG) sseHeaders['adk_config_id'] = configId
        // Tenant owner — lets searchKnowledge resolve the company even for the general
        // per-tenant interactive agent (whose configId is not an AdkAgentConfig row).
        if (ownerPartyId) sseHeaders['adk_owner_party_id'] = ownerPartyId
        // test run: the MCP server reports writes instead of executing them
        if (dryRun) sseHeaders['adk_dry_run'] = 'Y'
        // Check system properties ('port' is commonly used by Moqui runner like -Dport=8081)
        String mcpInternalPort = System.getProperty('webapp_http_port') ?: System.getProperty('port') ?: System.getenv('webapp_http_port')
        String mcpInternalHost = System.getProperty('webapp_http_host') ?: System.getenv('webapp_http_host')
        
        // Moqui executable (moqui.war) parses port=8081 but doesn't set it in System properties.
        // We can extract it from the command line arguments.
        String sunJavaCommand = System.getProperty("sun.java.command")
        if (!mcpInternalPort && sunJavaCommand != null) {
            java.util.regex.Matcher match = sunJavaCommand =~ /(?:^|\s)port=(\d+)/
            if (match.find()) {
                mcpInternalPort = match.group(1)
            }
        }

        if ((!mcpInternalPort || !mcpInternalHost) && sharedSessionService != null && sharedSessionService.ecf instanceof org.moqui.impl.context.ExecutionContextFactoryImpl) {
            try {
                // Read from actual parsed XML config
                def confRoot = sharedSessionService.ecf.getConfXmlRoot()
                def webroot = confRoot.first("webapp-list")?.children("webapp")?.find { it.attribute("name") == "webroot" }
                if (webroot != null) {
                    if (!mcpInternalPort && webroot.attribute("http-port")) {
                        String parsedPort = webroot.attribute("http-port")
                        if (!parsedPort.startsWith("\$")) {
                            mcpInternalPort = parsedPort
                        } else if (parsedPort == "\${webapp_http_port:-8080}") {
                            mcpInternalPort = '8080' // default fallback if unexpanded
                        }
                    }
                    if (!mcpInternalHost && webroot.attribute("http-host")) {
                        String parsedHost = webroot.attribute("http-host")
                        if (!parsedHost.startsWith("\$")) {
                            mcpInternalHost = parsedHost
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not read webapp http config from configuration", e)
            }
        }
        mcpInternalPort = mcpInternalPort ?: '8080'
        mcpInternalHost = mcpInternalHost ?: '127.0.0.1'
        if (mcpInternalHost == '0.0.0.0' || mcpInternalHost == '::') mcpInternalHost = '127.0.0.1'

        String sseUrl = "http://${mcpInternalHost}:${mcpInternalPort}/mcp/sse"
        logger.info("Initializing MCP Toolset connecting to: ${sseUrl}")
        def sseParams = com.google.adk.tools.mcp.SseServerParameters.builder()
                .url(sseUrl)
                .headers(sseHeaders)
                .build()
        // a test-run toolset is private to that run: leave the agent's cached toolset alone
        def prior = dryRun ? null : configMcpToolsets[configId]
        if (prior != null) { try { prior.close() } catch (Exception ignore) {} }
        def ts
        if (toolMode == 'readOnly') {
            com.google.adk.tools.ToolPredicate filter = { com.google.adk.tools.BaseTool tool, Optional ctx ->
                tool.name() != MCP_WRITE_TOOL_NAME
            } as com.google.adk.tools.ToolPredicate
            ts = new com.google.adk.tools.mcp.McpToolset(sseParams, new com.fasterxml.jackson.databind.ObjectMapper(), filter)
        } else {
            ts = new com.google.adk.tools.mcp.McpToolset(sseParams)
        }
        if (dryRun) return ts
        configMcpToolsets[configId] = ts
        if (mcpToolset == null) mcpToolset = ts   // keep a default reference for legacy paths
        return ts
    }

    /** Build a toolset for a tenant-registered external MCP server.
     *  Unlike the built-in MCP toolset, this never sends SystemSupport credentials.
     *  The transport choice is persisted in AdkMcpServer.transport: 'http' means
     *  MCP Streamable HTTP, while 'sse' preserves legacy HTTP+SSE support.
     *
     *  Header values are encrypted in the entity and must never appear in logs.
     *  OAuth access-token renewal is NOT implemented here: a configured Bearer
     *  header is a static credential, not an OAuth client session.
     */
    /** Parse and validate an external MCP connection without opening a network socket.
     *  Used by both the transport builder and dedicated regression tests.
     */
    static Map<String, Object> externalMcpConnectionConfig(def srv) {
        String transport = (srv.transport ?: 'sse').toString().trim().toLowerCase(Locale.ROOT)
        if (!(transport in ['http', 'sse'])) {
            throw new IllegalArgumentException("Unsupported external MCP transport: ${transport}")
        }
        String endpoint = (srv.url ?: '').toString().trim()
        URI endpointUri
        try {
            endpointUri = new URI(endpoint)
        } catch (Exception ignored) {
            // URISyntaxException can echo the submitted URL, which may contain credentials.
            throw new IllegalArgumentException('Invalid external MCP URL')
        }
        String scheme = endpointUri.scheme?.toLowerCase(Locale.ROOT)
        String host = endpointUri.host?.toLowerCase(Locale.ROOT)
        if (!host || endpointUri.userInfo || endpointUri.fragment ||
                !(scheme == 'https' || (scheme == 'http' && host in ['localhost', '127.0.0.1', '::1']))) {
            throw new IllegalArgumentException('External MCP URL must be HTTPS (HTTP only on loopback), without credentials or fragment')
        }

        Map<String, String> headers = ['Accept': 'application/json, text/event-stream']
        String hj = srv.headersJson as String
        if (hj) {
            def parsed
            try {
                parsed = new groovy.json.JsonSlurper().parseText(hj)
            } catch (Exception ignored) {
                throw new IllegalArgumentException('Invalid external MCP headers JSON')
            }
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('External MCP headers must be a JSON object')
            parsed.each { k, v ->
                if (k == null || v == null) return
                String name = k.toString().trim()
                String value = v.toString()
                // Refuse header injection and hop-by-hop/authority overrides.
                if (!(name ==~ /[A-Za-z0-9!#\$%&'*+.^_`|~-]+/) ||
                        value.contains('\r') || value.contains('\n') ||
                        name.toLowerCase(Locale.ROOT) in ['host', 'connection', 'content-length', 'transfer-encoding']) {
                    throw new IllegalArgumentException('Invalid or forbidden external MCP header')
                }
                headers[name] = value
            }
        }

        return [transport: transport, endpoint: endpoint, headers: headers]
    }

    /** Construct the actual SDK transport parameters without opening an HTTP connection.
     *  The JUnit suite checks both SDK types so 'http' can never silently fall back to SSE.
     */
    static Object externalMcpTransportParameters(def srv) {
        def connection = externalMcpConnectionConfig(srv)
        String transport = connection.transport as String
        String endpoint = connection.endpoint as String
        Map<String, String> headers = connection.headers as Map<String, String>

        if (transport == 'http') {
            return com.google.adk.tools.mcp.StreamableHttpServerParameters.builder()
                    .url(endpoint)
                    .headers(headers)
                    .build()
        }
        return com.google.adk.tools.mcp.SseServerParameters.builder()
                .url(endpoint)
                .headers(headers)
                .build()
    }

    private static com.google.adk.tools.mcp.McpToolset buildExternalMcpToolset(def srv) {
        return new com.google.adk.tools.mcp.McpToolset(externalMcpTransportParameters(srv))
    }

    /** Build the toolsets for every enabled external MCP server attached to this agent.
     *  Owner-guarded (a server must belong to the agent's tenant). Prior toolsets for the
     *  configId are closed first so reloadConfig doesn't leak SSE connections. */
    private static List<com.google.adk.tools.mcp.McpToolset> loadExternalMcpToolsets(String configId, String ownerPartyId) {
        List<com.google.adk.tools.mcp.McpToolset> out = []
        if (shuttingDown) return out
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return out
        def prior = configExternalToolsets.remove(configId)
        if (prior) prior.each { try { it?.close() } catch (Exception ignore) {} }
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def links = ec.entity.find('moqui.adk.AdkAgentMcpServer')
                        .condition('configId', configId).condition('enabled', 'Y')
                        .orderBy('sequenceNum').list()
                for (def lnk in links) {
                    def srv = ec.entity.find('moqui.adk.AdkMcpServer')
                            .condition('adkMcpServerId', lnk.adkMcpServerId as String).one()
                    if (!srv || srv.enabled != 'Y') continue
                    // tenant guard: the server must belong to the agent's company
                    if (ownerPartyId && srv.ownerPartyId && (srv.ownerPartyId as String) != (ownerPartyId as String)) {
                        logger.warn("Skipping cross-tenant MCP server ${srv.adkMcpServerId} (owner ${srv.ownerPartyId}) on agent ${configId} (owner ${ownerPartyId})")
                        continue
                    }
                    try {
                        out.add(buildExternalMcpToolset(srv))
                    } catch (Exception e) {
                        logger.warn("Failed to build external MCP toolset ${srv.adkMcpServerId} for ${configId}: ${e.message}")
                    }
                }
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("loadExternalMcpToolsets failed for ${configId}: ${e.message}")
        }
        if (out) configExternalToolsets[configId] = out
        return out
    }

    /** Read an agent's toolMode (readOnly | scoped | full) from its persisted config.
     *  Defaults to readOnly when unknown so new/unconfigured agents are safe by default. */
    private static String lookupToolMode(String configId) {
        // Synthetic HTTP chat runners have no saved, approved write policy.
        if (configId?.startsWith('HTTP_INTERACTIVE_')) return 'readOnly'
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return 'full'
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()
                // null toolMode = legacy row (pre-governance) → keep full tool access.
                return (cfg?.toolMode ?: 'full') as String
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception e) {
            logger.warn("lookupToolMode failed for ${configId}: ${e.message}")
            return 'readOnly'
        }
    }

    /** True when the agent works the task loop (AdkLoopServices.run#AgentLoop). */
    private static boolean lookupLoopEnabled(String configId) {
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return false
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                return ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()?.loopEnabled == 'Y'
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception e) {
            logger.warn("lookupLoopEnabled failed for ${configId}: ${e.message}")
            return true
        }
    }

    /** The agent's persisted maxLlmCalls, or null when unset/unknown. */
    private static Integer lookupMaxLlmCalls(String configId) {
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return null
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()
                return cfg?.maxLlmCalls as Integer
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception e) {
            logger.warn("lookupMaxLlmCalls failed for ${configId}: ${e.message}")
            return null
        }
    }

    /** True when the agent's persisted config opts in to the Google Search tool (webSearch='Y'). */
    private static boolean lookupWebSearch(String configId) {
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return false
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()
                return cfg?.webSearch == 'Y'
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception e) {
            logger.warn("lookupWebSearch failed for ${configId}: ${e.message}")
            return false
        }
    }

    // ── Phase 4: multi-agent orchestration ──────────────────────────────────────

    /** Read a config's orchestration role: [role, type, loopMax]. null for rows that don't
     *  exist (DEFAULT/INTERACTIVE) or plain specialists. */
    private static Map lookupOrchestration(String configId) {
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return null
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()
                String role = cfg?.agentRole as String
                if (!role || role == 'specialist') return null
                return [role: role, type: (cfg.orchestrationType ?: 'router') as String,
                        loopMax: cfg.loopMaxIterations as Integer]
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("lookupOrchestration failed for ${configId}: ${e.message}")
            return null
        }
    }

    /** Enabled team members of a coordinator, owner-scoped (cross-tenant members rejected),
     *  ordered by sequenceNum. Each entry: [memberConfigId, agentName, description, delegationMode]. */
    private static List<Map> loadTeamMembers(String coordinatorConfigId, String ownerPartyId) {
        List<Map> out = []
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def rows = ec.entity.find('moqui.adk.AdkAgentTeamMember')
                        .condition('coordinatorConfigId', coordinatorConfigId)
                        .condition('enabled', 'Y').orderBy('sequenceNum').list()
                for (def r in rows) {
                    def mc = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('adkAgentConfigId', r.memberConfigId as String).one()
                    if (!mc) continue
                    // tenant guard: a member must belong to the coordinator's company
                    if (ownerPartyId && mc.ownerPartyId && (mc.ownerPartyId as String) != (ownerPartyId as String)) {
                        logger.warn("Skipping cross-tenant team member ${r.memberConfigId} (owner ${mc.ownerPartyId}) of coordinator ${coordinatorConfigId} (owner ${ownerPartyId})")
                        continue
                    }
                    out.add([memberConfigId: r.memberConfigId as String,
                             agentName: mc.agentName as String,
                             description: mc.description as String,
                             delegationMode: (r.delegationMode ?: 'tool') as String])
                }
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) {
            logger.warn("loadTeamMembers failed for ${coordinatorConfigId}: ${e.message}")
        }
        return out
    }

    /** Return a member agent's LlmAgent, building it on demand from its config row. Cycle-safe
     *  (returns null if the config is mid-build). */
    private static LlmAgent ensureAgentBuilt(String configId) {
        if (!configId) return null
        if (agentRegistry.containsKey(configId)) return agentRegistry[configId]
        if (sharedSessionService == null || buildingConfigs.contains(configId)) return agentRegistry[configId]
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            def cfg
            try {
                cfg = ec.entity.find('moqui.adk.AdkAgentConfig').condition('adkAgentConfigId', configId).one()
            } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            if (!cfg) return null
            String provider = cfg.llmProvider ?: 'gemini'
            String key = (cfg.apiKey as String) ?:
                    resolveTenantKey(cfg.ownerPartyId as String, provider)
            initConfig(configId, cfg.ownerPartyId as String, cfg.agentName as String,
                    cfg.modelName as String,
                    withParameters(cfg.instruction as String, cfg.parameters as String), key, provider,
                    cfg.description as String)
        } catch (Exception e) {
            logger.warn("ensureAgentBuilt failed for ${configId}: ${e.message}")
        }
        return agentRegistry[configId]
    }

    /** Build a coordinator LlmAgent that delegates to its team members as AgentTools (router).
     *  sequential/parallel/loop orchestration is Phase 4b — for now it routes via AgentTool too. */
    /** The in-process FunctionTools every agent gets (read-only set, plus write tools when allowed). */
    /** HTTP runners must not expose legacy helpers that trust model-supplied tenant IDs. */
    private static List assembleHttpFunctionTools() {
        List tools = [com.google.adk.tools.LoadArtifactsTool.INSTANCE]
        tools.addAll(com.google.adk.tools.FunctionTool.create(HelloTimeAgent.class, 'getCurrentTime'))
        return tools
    }

    private static List assembleFunctionTools(boolean allowWrites, boolean webSearch = false, def modelArg = null) {
        List allTools = new ArrayList()
        // lets the agent pull saved artifacts (PDFs/images) into context only when needed
        allTools.add(com.google.adk.tools.LoadArtifactsTool.INSTANCE)
        allTools.addAll(com.google.adk.tools.FunctionTool.create(HelloTimeAgent.class, 'getCurrentTime'))
        // Website-chat human handoff: safe (routes a conversation, no business-data write), so the
        // read-only Support agent can call it. Reads the active room from session state.
        allTools.addAll(com.google.adk.tools.FunctionTool.create(HandoffTool.class, 'requestHumanHandoff'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(EmailTool.class, 'readEmails'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getLatestTestRun'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getTestExceptions'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getMainSha'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getFileContent'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'listSubstackPosts'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'getSubstackPostComments'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'getSubstackEngagements'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'getSubscriberSyncStats'))
        if (allowWrites) {
            allTools.addAll(com.google.adk.tools.FunctionTool.create(EmailTool.class, 'sendEmail'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'createBranch'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'updateFileContent'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'createPullRequest'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'addComment'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'postSubstackNote'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'publishSubstackArticle'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(SubstackTool.class, 'addSubstackSubscriber'))
        }
        // Gemini Google Search, wrapped in its own sub-agent: the raw GoogleSearchTool can't be
        // mixed with function tools in one request. modelArg is a model-id String when the key
        // comes from the environment, so resolve it through the registry.
        if (webSearch && modelArg != null) {
            def searchModel = modelArg instanceof com.google.adk.models.BaseLlm ?
                    modelArg : com.google.adk.models.LlmRegistry.getLlm(modelArg as String)
            allTools.add(com.google.adk.tools.GoogleSearchAgentTool.create(searchModel))
        }
        return allTools
    }

    /** Build a FRESH LlmAgent instance for a workflow sub-agent (Sequential/Parallel/Loop set a
     *  parent on their sub-agents, so we must not reuse the registry-shared instance). It reuses the
     *  member's already-built McpToolset (same adk_config_id/owner → governance preserved, no extra
     *  SSE connection). Not registered in the registry. */
    private static com.google.adk.agents.BaseAgent buildMemberInstance(String memberConfigId, def modelArg) {
        if (!memberConfigId) return null
        ensureAgentBuilt(memberConfigId)   // ensures the cached McpToolset + standalone runner exist
        def cfg
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try { cfg = ec.entity.find('moqui.adk.AdkAgentConfig').condition('adkAgentConfigId', memberConfigId).one() }
            finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception e) { logger.warn("buildMemberInstance(${memberConfigId}) load failed: ${e.message}"); return null }
        if (!cfg) return null
        boolean allowWrites = (cfg.toolMode ?: 'full') != 'readOnly'
        boolean webSearch = cfg.webSearch == 'Y' && (cfg.llmProvider ?: 'gemini') == 'gemini'
        List tools = assembleFunctionTools(allowWrites, webSearch, modelArg)
        def ts = configMcpToolsets[memberConfigId]
        if (ts) tools.add(ts)
        return LlmAgent.builder()
                .name(sanitizeAgentName((cfg.agentName ?: memberConfigId) as String))
                .description((cfg.description ?: cfg.agentName ?: 'NASQO agent') as String)
                .instruction(CONTEXT_PREAMBLE + withParameters(cfg.instruction as String, cfg.parameters as String))
                .model(modelArg)
                .tools(tools)
                .build()
    }

    private static LlmAgent buildOrchestrator(String configId, String ownerPartyId,
                                              String agentName, def modelArg, Map orch) {
        def members = loadTeamMembers(configId, ownerPartyId)
        if (!members) {
            logger.warn("Coordinator ${configId} has no enabled team members — leaving as a plain agent")
            return null
        }
        String type = orch.type ?: 'router'

        // ── Deterministic workflows: Sequential / Parallel / Loop run their sub-agents (no LLM
        //    picking). Sub-agents must be FRESH instances (they get a parent). ──
        if (type in ['sequential', 'parallel', 'loop']) {
            List<com.google.adk.agents.BaseAgent> subAgents = []
            buildingConfigs.add(configId)
            try {
                for (def m in members) {
                    if (m.memberConfigId == configId) continue
                    def inst = buildMemberInstance(m.memberConfigId as String, modelArg)
                    if (inst != null) subAgents.add(inst)
                }
            } finally { buildingConfigs.remove(configId) }
            if (subAgents.isEmpty()) {
                logger.warn("Workflow ${configId}: no member agents could be built — leaving as a plain agent")
                return null
            }
            String wfName = sanitizeAgentName(agentName ?: (type + '_' + configId))
            String wfDesc = "NASQO ${type} workflow over ${subAgents.size()} specialist(s)"
            switch (type) {
                case 'sequential':
                    return com.google.adk.agents.SequentialAgent.builder()
                            .name(wfName).description(wfDesc).subAgents(subAgents).build()
                case 'parallel':
                    return com.google.adk.agents.ParallelAgent.builder()
                            .name(wfName).description(wfDesc).subAgents(subAgents).build()
                case 'loop':
                    return com.google.adk.agents.LoopAgent.builder()
                            .name(wfName).description(wfDesc)
                            .maxIterations((orch.loopMax ?: 3) as Integer)
                            .subAgents(subAgents).build()
            }
        }

        // ── Router (default): LLM picks a specialist, each wrapped as an AgentTool. ──
        buildingConfigs.add(configId)
        List memberTools = []
        List<String> roster = []
        try {
            for (def m in members) {
                if (m.memberConfigId == configId) continue   // no self-membership
                LlmAgent ma = ensureAgentBuilt(m.memberConfigId)
                if (ma == null) continue
                memberTools.add(com.google.adk.tools.AgentTool.create(ma))
                roster.add('- ' + ma.name() + (m.description ? (': ' + m.description) : ''))
            }
        } finally {
            buildingConfigs.remove(configId)
        }
        if (memberTools.isEmpty()) {
            logger.warn("Coordinator ${configId}: no member agents could be built — leaving as a plain agent")
            return null
        }
        // Give the coordinator its own Moqui MCP toolset too, so it can answer simple questions
        // and emit screen directives directly without always delegating.
        def own = configMcpToolsets[configId]
        if (own) memberTools.add(own)

        String coordInstr = CONTEXT_PREAMBLE + '''\
You are the COORDINATOR agent for this company. You lead a team of specialist agents, each
exposed to you as a tool you can call with a natural-language request:
''' + roster.join('\n') + '''

When a request matches a specialist, CALL that specialist's tool with the user's full request and
relay (or briefly summarise) its answer — never fabricate what a specialist would say. You may call
more than one specialist and combine their answers. For general questions you can answer directly or
use your own Moqui tools. Keep delegation minimal: pick the best-matching specialist, call it once.
'''
        return LlmAgent.builder()
                .name(agentName ?: ('coordinator_' + configId))
                .description('NASQO coordinator delegating to specialist agents')
                .instruction(coordInstr)
                .model(modelArg)
                .tools(memberTools)
                .build()
    }

    /** Close every McpToolset (per-config, external, default). Idempotent: a re-close is a
     *  no-op. Shared by destroy() and the JVM shutdown hook. */
    static void closeAllToolsets() {
        // Close all per-config toolsets first — this signals the SSE clients to stop
        // retrying before the HTTP port goes away, suppressing ConnectException floods.
        configMcpToolsets.values().each { ts ->
            try { ts?.close() } catch (Exception e) {
                if (e.message?.contains('ConnectException') || e.cause instanceof java.net.ConnectException)
                    logger.warn("McpToolset closed during shutdown (expected): ${e.message}")
                else
                    logger.warn("Error closing per-config McpToolset: ${e.message}")
            }
        }
        configMcpToolsets.clear()
        configExternalToolsets.values().each { list ->
            list?.each { ts ->
                try { ts?.close() } catch (Exception e) {
                    logger.warn("Error closing external McpToolset: ${e.message}")
                }
            }
        }
        configExternalToolsets.clear()
        if (mcpToolset != null) {
            try {
                logger.info('Closing ADK McpToolset...')
                mcpToolset.close()
            } catch (Exception e) {
                logger.warn("McpToolset close during shutdown: ${e.message}")
            }
            mcpToolset = null
        }
    }

    static void destroy() {
        shuttingDown = true
        closeAllToolsets()
        registry.clear()
        agentRegistry.clear()
        tenantRegistry.clear()
        sessionOwn.clear()
        turnCounts.clear()
        providerRegistry.clear()
        sharedSessionService = null
        mcpApiKey = null
        mcpKeyRetryAfterMs = 0L
    }

    /** Wraps an external (tenant-registered) MCP toolset so its resolved tool names are checked
     *  against every other toolset already attached to the same agent (the fixed internal names
     *  plus earlier external servers). The Java ADK has no tool_name_prefix — BaseTool.name is
     *  private final, so renaming isn't reachable through the public API — so a same-named tool
     *  on two servers still silently shadows one at the SDK level. This can't prevent that, but
     *  logs it instead of leaving it silent. Detection only, never blocks a call: govern#AgentAction
     *  is the actual enforcement point regardless of which client-visible tool name was used. */
    private static final class CollisionLoggingToolset implements com.google.adk.tools.BaseToolset {
        private final com.google.adk.tools.mcp.McpToolset delegate
        private final String configId
        private final String serverLabel
        private final Set<String> knownNames

        CollisionLoggingToolset(com.google.adk.tools.mcp.McpToolset delegate, String configId,
                                 String serverLabel, Set<String> knownNames) {
            this.delegate = delegate
            this.configId = configId
            this.serverLabel = serverLabel
            this.knownNames = knownNames
        }

        @Override
        io.reactivex.rxjava3.core.Flowable<com.google.adk.tools.BaseTool> getTools(com.google.adk.agents.ReadonlyContext ctx) {
            return delegate.getTools(ctx).doOnNext({ com.google.adk.tools.BaseTool tool ->
                synchronized (knownNames) {
                    if (!knownNames.add(tool.name())) {
                        logger.warn("MCP tool name collision on agent ${configId}: '${tool.name()}' from ${serverLabel} " +
                                "duplicates an already-attached tool of the same name; one silently shadows the other.")
                    }
                }
            } as io.reactivex.rxjava3.functions.Consumer)
        }

        @Override
        void close() throws Exception { delegate.close() }
    }
}
