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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.context.ExecutionContextFactory
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Serves the ADK Angular DevUI at /adk/* together with the ADK REST API.
 *
 * Static SPA assets are loaded from component://moqui-adk/screen/adk-ui/
 * (populated at Gradle build time by the extractAdkBrowserAssets task).
 *
 * ADK REST endpoints implemented:
 *   GET  /list-apps
 *   POST /apps/{app}/users/{uid}/sessions
 *   GET  /apps/{app}/users/{uid}/sessions[/{sid}]
 *   DELETE /apps/{app}/users/{uid}/sessions/{sid}
 *   POST /run        — synchronous, returns event array
 *   POST /run_sse    — Server-Sent Events stream
 */
class AdkDevServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(AdkDevServlet.class)

    static final String STATIC_ROOT = 'component://moqui-adk/screen/adk-ui'

    static final Map<String, String> MIME_TYPES = [
        '.html' : 'text/html; charset=utf-8',
        '.js'   : 'application/javascript',
        '.mjs'  : 'application/javascript',
        '.css'  : 'text/css',
        '.svg'  : 'image/svg+xml',
        '.json' : 'application/json',
        '.png'  : 'image/png',
        '.jpg'  : 'image/jpeg',
        '.ico'  : 'image/x-icon',
        '.woff' : 'font/woff',
        '.woff2': 'font/woff2',
        '.txt'  : 'text/plain',
    ].asImmutable()

    private static final String CALLER_ATTRIBUTE = 'moqui.adk.authenticatedCaller'

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
        if (!addCorsHeaders(req, resp)) {
            deny(resp, 403, 'Origin not allowed')
            return
        }
        if (req.method == 'OPTIONS') { resp.status = 204; return }
        resp.setHeader('Cache-Control', 'no-cache, no-store')
        ExecutionContextFactory factory = ecf(req)
        if (factory == null) { deny(resp, 503, 'System is initializing'); return }
        // Custom servlets do not inherit MoquiServlet's REST authorization.
        // Start with a clean request context, and authenticate without reading
        // the JSON body through WebFacade (which would consume its reader).
        factory.destroyActiveExecutionContext()
        try {
            def ec = factory.getExecutionContext()
            ec.user.initFromHttpRequest(req, resp)
            String userId = ec.user.userId
            if (!userId || req.getAttribute('moqui.login.error') == 'true') {
                deny(resp, 401, 'Authentication required')
                return
            }
            boolean admin = ec.user.isInGroup('ADMIN') || ec.user.isInGroup('GROWERP_M_ADMIN')
            if (!admin && !ec.user.isInGroup('AdkUsers')) {
                deny(resp, 403, 'ADK access required')
                return
            }
            def account = ec.entity.find('moqui.security.UserAccount')
                    .condition('userId', userId).disableAuthz().one()
            def party = account?.partyId ? ec.entity.find('mantle.party.Party')
                    .condition('partyId', account.partyId).disableAuthz().one() : null
            String owner = party?.ownerPartyId
            if (!owner) { deny(resp, 403, 'Company context required'); return }
            req.setAttribute(CALLER_ATTRIBUTE,
                    [userId: userId, ownerPartyId: owner, admin: admin].asImmutable())
            dispatchAuthorized(req, resp)
        } finally {
            factory.destroyActiveExecutionContext()
        }
    }

    private void dispatchAuthorized(HttpServletRequest req, HttpServletResponse resp) {
        String pathInfo = req.pathInfo ?: '/'
        String method = req.method
        // ── API routes ────────────────────────────────────────────────────────
        if (pathInfo == '/list-apps' || pathInfo == '/list-apps/') {
            AdkManager.lazyInit(ecf(req))
            handleListApps(resp)
            return
        }
        if (pathInfo.startsWith('/apps/')) {
            handleApps(pathInfo, method, req, resp)
            return
        }
        if (pathInfo == '/run' && method == 'POST') {
            handleRun(req, resp, false)
            return
        }
        if (pathInfo == '/run_sse' && method == 'POST') {
            handleRun(req, resp, true)
            return
        }
        if (pathInfo == '/configs' || pathInfo == '/configs/') {
            handleConfigs(pathInfo, method, req, resp)
            return
        }
        if (pathInfo.startsWith('/configs/')) {
            handleConfigs(pathInfo, method, req, resp)
            return
        }

        // ── Static SPA assets ─────────────────────────────────────────────────
        serveStatic(pathInfo, resp)
    }

    // ── API handlers ──────────────────────────────────────────────────────────

    private static Map caller(HttpServletRequest req) {
        req.getAttribute(CALLER_ATTRIBUTE) as Map
    }

    private static void deny(HttpServletResponse resp, int status, String message) {
        resp.status = status
        json(resp, [error: message])
    }

    private boolean ownsSession(HttpServletRequest req, HttpServletResponse resp, String sessionId) {
        Map principal = caller(req)
        def ec = ecf(req).getExecutionContext()
        def session = ec.entity.find('moqui.adk.AdkSession')
                .condition('adkSessionId', sessionId).condition('appName', AdkManager.APP_NAME)
                .condition('userId', principal.userId).disableAuthz().one()
        Map state = [:]
        try { state = new JsonSlurper().parseText(session?.stateJson ?: '{}') as Map }
        catch (Exception ignored) {}
        if (!session || state.tenantId != principal.ownerPartyId) {
            deny(resp, 404, 'Session not found')
            return false
        }
        return true
    }

    private void handleListApps(HttpServletResponse resp) {
        json(resp, AdkManager.listAgents())
    }

    private void handleApps(String path, String method, HttpServletRequest req, HttpServletResponse resp) {
        // path: /apps/{app}/users/{userId}/sessions[/{sessionId}]
        String[] parts = path.split('/')
        if (!(parts.length in [6, 7]) || parts[1] != 'apps' ||
                parts[2] != AdkManager.APP_NAME || parts[3] != 'users' || parts[5] != 'sessions') {
            deny(resp, 400, 'Invalid path'); return
        }
        Map principal = caller(req)
        String userId = parts[4]
        String sessionId = parts.length > 6 ? parts[6] : null
        if (userId != principal.userId) { deny(resp, 403, 'User does not match caller'); return }
        if (sessionId && !ownsSession(req, resp, sessionId)) return
        if (method == 'POST' && sessionId) { deny(resp, 400, 'Invalid session creation path'); return }
        if (method == 'DELETE' && !sessionId) { deny(resp, 400, 'Session ID required'); return }

        switch (method) {
            case 'POST':
                // Read the JSON body once; only the screen catalog is client-supplied.
                Map clientState = [:]
                try {
                    String rawBody = req.reader.text
                    if (rawBody) {
                        def parsed = new JsonSlurper().parseText(rawBody)
                        if (parsed instanceof Map && parsed.state instanceof Map) {
                            clientState = parsed.state as Map
                        }
                    }
                } catch (Exception ignored) {}
                Map<String, Object> initialState = buildContext(req, resp)
                // Only UI context may be supplied by the client. Identity,
                // company and memory always come from the authenticated caller.
                if (clientState.containsKey('screenCatalog')) {
                    initialState.screenCatalog = clientState.screenCatalog
                }
                initialState.currentUserId = principal.userId
                try {
                    AdkManager.lazyInit(ecf(req))
                    json(resp, AdkManager.createSession(userId, initialState, true))
                } catch (IllegalStateException e) {
                    // Expected when no LLM key is configured — return a clean 503 + JSON
                    // (the chat UI matches the message to prompt for System Setup) instead
                    // of letting it bubble to Jetty as a 500 with a stack trace.
                    logger.warn("ADK session not created: ${e.message}")
                    resp.status = 503
                    json(resp, [error: e.message])
                }
                break
            case 'GET':

                if (sessionId) {
                    AdkManager.lazyInit(ecf(req))
                    def s = AdkManager.getSession(userId, sessionId)
                    if (!s) { resp.sendError(404); return }
                    json(resp, s)
                } else {
                    // Filter by both user and tenant before returning identifiers.
                    def ec = ecf(req).getExecutionContext()
                    def sessions = ec.entity.find('moqui.adk.AdkSession')
                            .condition('appName', AdkManager.APP_NAME).condition('userId', userId)
                            .disableAuthz().list()
                    json(resp, sessions.findAll { entry ->
                        try {
                            (new JsonSlurper().parseText(entry.stateJson ?: '{}') as Map).tenantId ==
                                    principal.ownerPartyId
                        } catch (Exception ignored) { false }
                    }.collect { entry -> [id: entry.adkSessionId, appName: AdkManager.APP_NAME, userId: userId] })
                }
                break
            case 'DELETE':
                AdkManager.lazyInit(ecf(req))
                if (sessionId) AdkManager.deleteSession(userId, sessionId)
                resp.status = 200
                break
            default:
                resp.sendError(405)
        }
    }

    private void handleRun(HttpServletRequest req, HttpServletResponse resp, boolean sse) {

        Map body
        try { body = new JsonSlurper().parseText(req.reader.text ?: '{}') as Map }
        catch (Exception ignored) { deny(resp, 400, 'Invalid JSON body'); return }
        Map principal = caller(req)
        String userId = principal.userId
        if (body.userId != userId || body.appName != AdkManager.APP_NAME) {
            deny(resp, 403, 'User or application does not match caller'); return
        }
        String sid = (body.sessionId ?: '') as String
        String text = (body.newMessage?.parts?.find { it.text }?.text ?: '') as String
        if (!sid) { deny(resp, 400, 'Session ID required'); return }
        if (!ownsSession(req, resp, sid)) return
        AdkManager.lazyInit(ecf(req))

        if (sse) {
            resp.contentType = 'text/event-stream; charset=utf-8'
            resp.setHeader('X-Accel-Buffering', 'no')
            resp.setHeader('Connection', 'keep-alive')
            def writer = resp.writer
            AdkManager.runAgentSse(userId, sid, text,
                { Map event ->
                    writer.write("data: ${JsonOutput.toJson(event)}\n\n")
                    writer.flush()
                },
                { Throwable err ->
                    if (err) {
                        logger.error("ADK run_sse error (session=${sid}): ${err.message}", err)
                        writer.write("data: ${JsonOutput.toJson([error: err.message])}\n\n")
                    }
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                }
            )
        } else {
            json(resp, AdkManager.runAgent(userId, sid, text))
        }
    }

    // ── Agent config CRUD (/adk/configs) ─────────────────────────────────────

    private void handleConfigs(String path, String method, HttpServletRequest req, HttpServletResponse resp) {
        ExecutionContextFactory ecf = ecf(req)

        // Read the JSON body once after the request authentication guard.
        String rawBody = (method == 'POST') ? req.reader.text : null

        // configId from path: /configs/{configId}
        String[] parts = path.split('/')
        String configId = parts.length > 2 ? parts[2] : null

        def ec = ecf.getExecutionContext()
        try {
            Map principal = caller(req)
            String ownerPartyId = principal.ownerPartyId
            if (method != 'GET' && !principal.admin) {
                deny(resp, 403, 'Company administrator access required'); return
            }

            switch (method) {
                case 'GET':
                    boolean wasDisabled = ec.artifactExecution.disableAuthz()
                    try {
                        def find = ec.entity.find('moqui.adk.AdkAgentConfig')
                        find = find.condition('ownerPartyId', ownerPartyId)
                        def list = find.list().collect { cfg ->
                            [adkAgentConfigId   : cfg.adkAgentConfigId,
                             agentName          : cfg.agentName,
                             modelName          : cfg.modelName,
                             instruction        : cfg.instruction,
                             description        : cfg.description,
                             enabled            : cfg.enabled,
                             scheduleExpression : cfg.scheduleExpression,
                             scheduleEnabled    : cfg.scheduleEnabled,
                             schedulePrompt     : cfg.schedulePrompt,
                             scheduleChatRoomId : cfg.scheduleChatRoomId]
                        }
                        json(resp, list)
                    } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
                    break

                case 'POST':
                    def body = new JsonSlurper().parseText(rawBody ?: '{}') as Map
                    if (body.ownerPartyId && body.ownerPartyId != ownerPartyId) {
                        deny(resp, 403, 'Company does not match caller'); return
                    }
                    Map params = [
                        ownerPartyId      : ownerPartyId,
                        agentName         : body.agentName,
                        // no default here: an empty modelName lets AdkManager.defaultModelFor
                        // resolve it at agent-build time (SystemDefault -> env -> constant)
                        modelName         : body.modelName,
                        instruction       : body.instruction,
                        description       : body.description,
                        scheduleExpression: body.scheduleExpression,
                        scheduleEnabled   : body.scheduleEnabled ?: 'N',
                        schedulePrompt    : body.schedulePrompt,
                        scheduleChatRoomId: body.scheduleChatRoomId,
                    ]
                    if (body.apiKey) params.apiKey = body.apiKey
                    // The request guard requires an authenticated tenant admin;
                    // this operation is pinned to that caller's ownerPartyId.
                    boolean wasDisabledPost = ec.artifactExecution.disableAuthz()
                    Map result
                    try {
                        result = ec.service.sync()
                            .name('AdkServices.update#AgentConfig')
                            .parameters(params).call()
                    } finally { if (!wasDisabledPost) ec.artifactExecution.enableAuthz() }
                    json(resp, [adkAgentConfigId: result.adkAgentConfigId])
                    break

                case 'DELETE':
                    if (!configId) { resp.sendError(400, 'configId required'); return }
                    boolean wasDisabled2 = ec.artifactExecution.disableAuthz()
                    try {
                        def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                            .condition('adkAgentConfigId', configId)
                            .condition('ownerPartyId', ownerPartyId).one()
                        if (!cfg) { resp.sendError(404); return }
                        cfg.delete()
                        resp.status = 200
                        json(resp, [deleted: configId])
                    } finally { if (!wasDisabled2) ec.artifactExecution.enableAuthz() }
                    break

                default:
                    resp.sendError(405)
            }
        } finally {
            // The service method owns and closes the request execution context.
        }
    }

    // ── Static file serving ───────────────────────────────────────────────────

    private void serveStatic(String path, HttpServletResponse resp) {
        if (path == '/' || path.isEmpty()) path = '/index.html'
        // SPA client-side routing: paths without a file extension → index.html
        if (!path.contains('.')) path = '/index.html'

        String ext      = path.contains('.') ? path.substring(path.lastIndexOf('.')) : ''
        resp.contentType = (MIME_TYPES[ext] ?: 'application/octet-stream')

        ExecutionContextFactory ecf =
            (ExecutionContextFactory) getServletContext().getAttribute('executionContextFactory')

        if (ecf == null) { resp.sendError(503, 'ExecutionContextFactory not available'); return }

        ResourceReference ref = ecf.resource.getLocationReference("${STATIC_ROOT}${path}")
        if (!ref || !ref.getExists()) {
            if (path != '/index.html') {
                serveStatic('/index.html', resp)  // SPA fallback
            } else {
                resp.sendError(404, "ADK DevUI assets not found. Run './gradlew build' first.")
            }
            return
        }

        InputStream stream = ref.openStream()
        try {
            resp.outputStream << stream
        } finally {
            stream?.close()
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private ExecutionContextFactory ecf(HttpServletRequest req) {
        (ExecutionContextFactory) req.servletContext.getAttribute('executionContextFactory')
    }

    private static boolean addCorsHeaders(HttpServletRequest req, HttpServletResponse resp) {
        String origin = req.getHeader('Origin')
        if (origin) {
            try {
                URI uri = new URI(origin)
                String authority = req.getHeader('Host') ?: req.serverName
                String scheme = req.getHeader('X-Forwarded-Proto')?.split(',')?.first()?.trim() ?: req.scheme
                if (uri.scheme != scheme || !uri.rawAuthority?.equalsIgnoreCase(authority) ||
                        (uri.rawPath && uri.rawPath != '/') || uri.rawQuery || uri.rawFragment) return false
            } catch (Exception ignored) { return false }
            resp.setHeader('Access-Control-Allow-Origin', origin)
            resp.setHeader('Vary', 'Origin')
            resp.setHeader('Access-Control-Allow-Credentials', 'true')
        }
        resp.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
        resp.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, api_key, moquiSessionToken')
        resp.setHeader('Access-Control-Max-Age', '3600')
        return true
    }

    /**
     * Build a session-state map from the Moqui ExecutionContext so that the ADK
     * agent's instruction preamble can resolve {key} placeholders.
     *
     * Gathered fields: userId, username, userFullName, organizationName,
     * companyPseudoId, tenantId (ownerPartyId), timeZone, locale.
     */
    private Map<String, Object> buildContext(HttpServletRequest req, HttpServletResponse resp) {
        Map<String, Object> ctx = [
            userId          : caller(req).userId,
            username        : '',
            userFullName    : '',
            organizationName: '',
            companyPseudoId : '',
            tenantId        : caller(req).ownerPartyId,
            timeZone        : 'UTC',
            locale          : 'en_US',
            // Default for the {screenCatalog} instruction placeholder; the Flutter
            // client overrides this with the real catalog on session create.
            screenCatalog   : '[]',
            // {memory} — rolling per-(owner,user) summary; filled below when present.
            memory          : '',
        ]
        try {
            ExecutionContextFactory ecf = ecf(req)
            if (!ecf) return ctx
            def ec = ecf.getExecutionContext()
            try {
                // Authentication already ran before dispatch without consuming
                // the body. Never initialize or replace identity from client state.
                if (ec.user?.userId) {
                    ctx.userId   = ec.user.userId   ?: 'anonymous'
                    ctx.username = ec.user.username ?: 'anonymous'
                    ctx.timeZone = ec.user.timeZone?.ID ?: 'UTC'
                    ctx.locale   = ec.user.locale?.toString() ?: 'en_US'
                }

                // Resolve full name, organization, company pseudoId, and ownerPartyId (GrowERP tenant)
                if (ec.user?.userId) {
                    try {
                        boolean wasDisabled = ec.artifactExecution.disableAuthz()
                        try {
                            // userFullName lives on UserAccount; UserFacade has no getter for it
                            def userAcct = ec.entity.find('moqui.security.UserAccount')
                                .condition('userId', ec.user.userId)
                                .selectField('userFullName').selectField('partyId')
                                .one()
                            ctx.userFullName = userAcct?.userFullName ?: ''

                            // ownerPartyId is GrowERP's tenant identifier — on mantle.party.Party
                            String userPartyId = userAcct?.partyId
                            if (userPartyId) {
                                def userParty = ec.entity.find('mantle.party.Party')
                                    .condition('partyId', userPartyId)
                                    .selectField('ownerPartyId')
                                    .one()
                                String ownerPartyId = userParty?.ownerPartyId
                                if (ownerPartyId) {
                                    ctx.tenantId = ownerPartyId

                                    // Load rolling memory summary for this (owner,user) → {memory}.
                                    def mem = ec.entity.find('moqui.adk.AdkMemory')
                                        .condition('ownerPartyId', ownerPartyId)
                                        .condition('userId', ec.user.userId)
                                        .one()
                                    if (mem?.summaryText) {
                                        ctx.memory = 'What you remember about this user/company from past ' +
                                            'conversations (use it for continuity; do not repeat it verbatim):\n' +
                                            mem.summaryText
                                    }

                                    // Main company = OrgInternal party for this owner
                                    def companyList = ec.entity.find('mantle.party.PartyDetailAndRole')
                                        .condition('ownerPartyId', ownerPartyId)
                                        .condition('partyTypeEnumId', 'PtyOrganization')
                                        .condition('roleTypeId', 'OrgInternal')
                                        .limit(1).list()
                                    String companyPartyId = companyList[0]?.partyId
                                    if (companyPartyId) {
                                        def org = ec.entity.find('mantle.party.Organization')
                                            .condition('partyId', companyPartyId).one()
                                        ctx.organizationName = org?.organizationName ?: ''

                                        def companyParty = ec.entity.find('mantle.party.Party')
                                            .condition('partyId', companyPartyId)
                                            .selectField('pseudoId').one()
                                        ctx.companyPseudoId = companyParty?.pseudoId ?: ''
                                    }
                                }
                            }
                        } finally {
                            if (!wasDisabled) ec.artifactExecution.enableAuthz()
                        }
                    } catch (Exception ignored) { /* best-effort */ }
                }
            } finally {
                // Keep the guard's request context until service() completes.
            }
        } catch (Exception e) {
            // Context is best-effort; never block session creation
        }
        ctx
    }

    private static void json(HttpServletResponse resp, Object data) {
        resp.contentType = 'application/json; charset=utf-8'
        resp.writer.write(JsonOutput.toJson(data))
    }

    @Override
    void destroy() {
        AdkManager.destroy()
        super.destroy()
    }
}
