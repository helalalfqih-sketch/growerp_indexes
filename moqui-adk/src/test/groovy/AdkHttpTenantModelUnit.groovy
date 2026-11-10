#!/usr/bin/env groovy
// Run with compiled ADK classes and their deployed dependency classpath.
// Uses the real manager and fake Moqui facades. No database, credentials or network.
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

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
def service = field('sharedSessionService')
Map registry = field('registry').get(null) as Map
Map agents = field('agentRegistry').get(null) as Map
Map tenants = field('tenantRegistry').get(null) as Map
String defaultId = field('DEFAULT_CONFIG').get(null) as String
def saved = [service: service.get(null), registry: new LinkedHashMap(registry),
             agents: new LinkedHashMap(agents), tenants: new LinkedHashMap(tenants)]

// EntityValue extends Map. A Java proxy handles both get(String) and get(Object)
// without replacing the real EntityFind.one() return type.
def entityValue = { Map row ->
    if (row == null) return null
    Proxy.newProxyInstance(EntityValue.classLoader, [EntityValue] as Class[], {
        Object proxy, java.lang.reflect.Method called, Object[] args ->
        switch (called.name) {
            case 'get': return row[args[0]]
            case 'getString': return row[args[0]]?.toString()
            case 'getMap': return row
            case 'containsKey': return row.containsKey(args[0])
            case 'isEmpty': return row.isEmpty()
            case 'size': return row.size()
            case 'keySet': return row.keySet()
            case 'values': return row.values()
            case 'entrySet': return row.entrySet()
            case 'toString': return 'TestEntityValue'
            case 'hashCode': return System.identityHashCode(proxy)
            case 'equals': return proxy.is(args[0])
            default: throw new UnsupportedOperationException('Unexpected EntityValue method: ' + called.name)
        }
    } as InvocationHandler) as EntityValue
}
Map settings = [:]
Map globalDefault = [defaultId: 'SYSTEM', aiProvider: 'gemini', aiModelName: 'global-gemini-model']
List reads = []
boolean authzDisabled = false
boolean settingsUnavailable = false
int disableCalls = 0
int enableCalls = 0
def artifacts = [
    disableAuthz: {
        boolean previous = authzDisabled
        authzDisabled = true
        disableCalls++
        previous
    },
    enableAuthz: { authzDisabled = false; enableCalls++; null }
] as ArtifactExecutionFacade
def entity = [find: { String entityName ->
    assert entityName in ['growerp.general.SystemSettings', 'growerp.general.SystemDefault']:
            'Model lookup queried an unrelated entity'
    Map conditions = [:]
    EntityFind query
    query = [
        condition: { String name, Object value -> conditions[name] = value; query },
        one: {
            assert authzDisabled
            reads.add([entity: entityName, conditions: new LinkedHashMap(conditions)])
            if (entityName == 'growerp.general.SystemSettings') {
                assert conditions.keySet() == ['ownerPartyId'] as Set:
                        'Tenant model query must constrain exactly its owner'
                assert conditions.ownerPartyId:
                        'Tenant model query must not be unscoped'
                if (settingsUnavailable) throw new IllegalStateException('test unavailable')
                return entityValue(settings[conditions.ownerPartyId] as Map)
            }
            assert conditions == [defaultId: 'SYSTEM']
            entityValue(globalDefault)
        }
    ] as EntityFind
    query
}] as EntityFacade
def ec = [getEntity: { entity }, getArtifactExecution: { artifacts }] as ExecutionContext
def ecf = [getExecutionContext: { ec }] as ExecutionContextFactory
Class sessionService = Class.forName('org.moqui.adk.MoquiSessionService')
def resolve = method('interactiveModelFor', String, String, Boolean.TYPE)
def settingsReads = { reads.findAll { it.entity == 'growerp.general.SystemSettings' } }
def model = { String owner, String provider, boolean strict ->
    reads.clear()
    resolve.invoke(null, owner, provider, strict)
}
String own = 'tenant-a'
String other = 'tenant-b'
String ownCid = 'HTTP_INTERACTIVE_' + own
String otherCid = 'HTTP_INTERACTIVE_' + other
String legacyCid = 'INTERACTIVE_' + own
String scheduledCid = 'scheduled-tenant-a'
def seedRegistry = {
    registry.clear(); agents.clear(); tenants.clear()
    [defaultId, ownCid, otherCid, legacyCid, scheduledCid].each { String cid ->
        registry[cid] = new Object()
        agents[cid] = new Object()
    }
    tenants[own] = ownCid
    tenants['alias-a'] = ownCid
    tenants[other] = otherCid
    tenants['legacy-a'] = legacyCid
    tenants['scheduled-a'] = scheduledCid
}
def assertOnlyOwnRemoved = {
    assert registry.keySet() == [defaultId, otherCid, legacyCid, scheduledCid] as Set
    assert agents.keySet() == registry.keySet()
    assert tenants == [(other): otherCid, 'legacy-a': legacyCid, 'scheduled-a': scheduledCid]
}
try {
    service.set(null, sessionService.getConstructor(ExecutionContextFactory).newInstance(ecf))
    settings[own] = [aiProvider: 'gemini', aiModelName: '  gemini-tenant-a  ']
    settings[other] = [aiProvider: 'gemini', aiModelName: 'gemini-tenant-b']
    assert model(own, 'gemini', true) == 'gemini-tenant-a'
    assert settingsReads()*.conditions == [[ownerPartyId: own]]
    assert reads.size() == 1: 'Matching tenant model must not fall back globally'
    assert !authzDisabled && disableCalls == enableCalls

    // The selected key provider must match the model provider.
    settings[own] = [aiProvider: 'anthropic', aiModelName: 'claude-own']
    assert model(own, 'gemini', true) == 'global-gemini-model'
    assert settingsReads()*.conditions == [[ownerPartyId: own]]
    assert reads.size() == 2
    assert model(own, 'anthropic', true) == 'claude-own'
    settings[own] = [aiProvider: ' gemini ', aiModelName: ' custom-owned-model ']
    assert model(own, 'gemini', true) == 'custom-owned-model'

    // Legacy settings without a provider use model-prefix inference.
    settings[own] = [aiModelName: 'claude-own-inferred']
    assert model(own, 'anthropic', true) == 'claude-own-inferred'
    ['gpt-test-model', 'o3-test-model'].each { String incompatible ->
        settings[own] = [aiModelName: incompatible]
        assert model(own, 'gemini', true) == 'global-gemini-model'
    }
    settings[own] = [aiModelName: 'gemini-own-inferred']
    assert model(own, 'gemini', true) == 'gemini-own-inferred'
    settings[own] = [aiProvider: 'gemini', aiModelName: '   ']
    assert model(own, 'gemini', true) == 'global-gemini-model'

    // Missing own settings never borrow another tenant's model.
    settings.remove(own)
    assert model(own, 'gemini', true) == 'global-gemini-model'
    assert settingsReads()*.conditions == [[ownerPartyId: own]]
    settings[own] = [aiProvider: 'gemini', aiModelName: 'gemini-private']
    assert model(own, 'gemini', false) == 'global-gemini-model'
    assert settingsReads().isEmpty(): 'Legacy callers must retain existing global model policy'
    assert model(null, 'gemini', true) == 'global-gemini-model'
    assert settingsReads().isEmpty()
    assert model('', 'gemini', true) == 'global-gemini-model'
    assert settingsReads().isEmpty()

    // Failed settings lookup falls back and restores the authz state.
    settingsUnavailable = true
    assert model(own, 'gemini', true) == 'global-gemini-model'
    assert !authzDisabled && disableCalls == enableCalls
    settingsUnavailable = false
    authzDisabled = true
    assert model(own, 'gemini', true) == 'gemini-private'
    assert authzDisabled: 'Model lookup must preserve already-disabled caller authz'
    authzDisabled = false

    seedRegistry()
    Map retained = new LinkedHashMap(registry)
    manager.invalidateHttpInteractive(own)
    assertOnlyOwnRemoved()
    registry.each { cid, runner -> assert runner.is(retained[cid]) }
    manager.invalidateHttpInteractive(own)
    manager.invalidateHttpInteractive(null)
    manager.invalidateHttpInteractive('')
    manager.invalidateHttpInteractive('unknown-owner')
    assertOnlyOwnRemoved()

    // An owner routed to its scheduled agent must keep that mapping.
    seedRegistry()
    tenants[own] = scheduledCid
    manager.invalidateHttpInteractive(own)
    assert tenants[own] == scheduledCid
    assert !tenants.containsKey('alias-a')
    assert registry.containsKey(scheduledCid)

    // A running transaction defers invalidation until its successful commit.
    List<Synchronization> callbacks = []
    boolean transactionActive = true
    def transaction = [
        isTransactionInPlace: { transactionActive },
        registerSynchronization: { Synchronization sync -> callbacks.add(sync); null }
    ] as TransactionFacade
    seedRegistry()
    manager.invalidateHttpInteractiveAfterCommit(transaction, own)
    assert callbacks.size() == 1
    assert registry.containsKey(ownCid)
    callbacks[0].beforeCompletion()
    assert registry.containsKey(ownCid)
    callbacks[0].afterCompletion(Status.STATUS_COMMITTED)
    assertOnlyOwnRemoved()

    // A rollback leaves the existing runner usable and preserves other tenants.
    seedRegistry()
    callbacks.clear()
    manager.invalidateHttpInteractiveAfterCommit(transaction, own)
    callbacks[0].afterCompletion(Status.STATUS_ROLLEDBACK)
    assert registry.keySet() == [defaultId, ownCid, otherCid, legacyCid, scheduledCid] as Set
    assert agents.keySet() == registry.keySet()
    assert tenants[own] == ownCid

    // No transaction uses immediate invalidation; empty owner registers nothing.
    callbacks.clear()
    transactionActive = false
    manager.invalidateHttpInteractiveAfterCommit(transaction, own)
    assert callbacks.isEmpty()
    assertOnlyOwnRemoved()
    seedRegistry()
    manager.invalidateHttpInteractiveAfterCommit(null, own)
    assertOnlyOwnRemoved()
    seedRegistry()
    transactionActive = true
    manager.invalidateHttpInteractiveAfterCommit(transaction, null)
    manager.invalidateHttpInteractiveAfterCommit(transaction, '')
    assert callbacks.isEmpty()
    assert registry.containsKey(ownCid)

    println 'ADK_HTTP_TENANT_MODEL_REGRESSION_PASS'
} finally {
    service.set(null, saved.service)
    registry.clear(); registry.putAll(saved.registry)
    agents.clear(); agents.putAll(saved.agents)
    tenants.clear(); tenants.putAll(saved.tenants)
}
