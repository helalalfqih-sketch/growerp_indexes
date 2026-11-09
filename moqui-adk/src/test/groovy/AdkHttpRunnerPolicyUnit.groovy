#!/usr/bin/env groovy
// Run with the compiled ADK classes and their deployed dependency classpath.
// No HTTP, provider requests, credentials or database access are needed.
import java.lang.reflect.InvocationTargetException

Class manager = Class.forName('org.moqui.adk.AdkManager')
def field = { String name ->
    def f = manager.getDeclaredField(name)
    f.accessible = true
    f
}
def method = { String name, Class... types ->
    def m = manager.getDeclaredMethod(name, types)
    m.accessible = true
    m
}
Map registry = field('registry').get(null) as Map
Map agents = field('agentRegistry').get(null) as Map
Map tenants = field('tenantRegistry').get(null) as Map
Map sessions = field('sessionOwn').get(null) as Map
String defaultId = field('DEFAULT_CONFIG').get(null) as String
def saved = [registry: new LinkedHashMap(registry), agents: new LinkedHashMap(agents),
             tenants: new LinkedHashMap(tenants), sessions: new LinkedHashMap(sessions)]
try {
    registry.clear(); agents.clear(); tenants.clear(); sessions.clear()
    def marker = new Object()
    [defaultId, 'INTERACTIVE_owner-a', 'HTTP_INTERACTIVE_owner-a', 'scheduled-owner-b'].each {
        registry.put(it, marker)
        agents.put(it, marker)
    }
    tenants.put('owner-a', 'HTTP_INTERACTIVE_owner-a')
    tenants.put('owner-b', 'scheduled-owner-b')
    sessions.put('foreign-session', 'scheduled-owner-b')

    // A known session routed to another config must not fall back to any runner.
    try {
        method('runnerForSession', String, String).invoke(null, 'foreign-session', 'owner-a')
        assert false: 'Foreign session runner was accepted'
    } catch (InvocationTargetException e) {
        assert e.cause instanceof IllegalStateException
        assert e.cause.message.contains('reopen the assistant')
    }
    assert method('lookupToolMode', String).invoke(null, 'HTTP_INTERACTIVE_owner-a') == 'readOnly'

    // The same helper used by reloadInteractive must invalidate rotated/deleted
    // keys for both old and HTTP runners while preserving scheduled agents.
    method('invalidateInteractiveRunners').invoke(null)
    assert registry.keySet() == ['scheduled-owner-b'] as Set
    assert agents.keySet() == ['scheduled-owner-b'] as Set
    assert tenants == ['owner-b': 'scheduled-owner-b']
    println 'ADK_HTTP_RUNNER_POLICY_REGRESSION_PASS'
} finally {
    registry.clear(); registry.putAll(saved.registry)
    agents.clear(); agents.putAll(saved.agents)
    tenants.clear(); tenants.putAll(saved.tenants)
    sessions.clear(); sessions.putAll(saved.sessions)
}
