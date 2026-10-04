package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryDefinitionsTests {

    private final Function<Map<String, Object>, Map<String, Object>> agent = request -> Map.of("status", "ok");
    private final Supplier<Object> capture = () -> null;
    private final Function<Object, AutoCloseable> reopen = snapshot -> null;
    private final InventoryDefinitions definitions = new InventoryDefinitions();

    @BeforeEach
    void install() throws Exception {
        reset();
        AgentBridge.install(agent);
        claim();
    }

    @AfterEach
    void reset() throws Exception {
        Method reset = AgentBridge.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void observingMoreLoadersThanTheCapacityDoesNotLeaseTheirTokens() throws Exception {
        List<ClassLoader> keep = new ArrayList<>();
        for (int i = 0; i < 17_000; i++) {
            ClassLoader loader = new ClassLoader(null) {};
            keep.add(loader);
            definitions.observed(loader, true);
        }
        assertThat(CodeInventory.status()).containsEntry("definitionOverflow", 0L);
        assertThat(CodeInventory.definitionToken()).isEqualTo(1);
        assertThat(leased()).isEmpty();
        Reference.reachabilityFence(keep);
    }

    @Test
    void weakCollectionDoesNotRecycleATokenBeforeItsPhantomRetires() throws Exception {
        ClassLoader loader = new ClassLoader(null) {};
        int token = definitions.token(loader);
        Reference<?> phantom = leased().iterator().next();
        Reference<?> weak = keys().iterator().next();
        weak.clear();
        assertThat(weak.enqueue()).isTrue();

        definitions.observed(new ClassLoader(null) {}, true);
        assertThat(CodeInventory.definitionToken()).isEqualTo(token + 1);
        assertThat(leased()).contains(phantom);

        assertThat(phantom.enqueue()).isTrue();
        definitions.observed(new ClassLoader(null) {}, true);
        assertThat(CodeInventory.eligibleDefinition(token)).isFalse();
        assertThat(CodeInventory.definitionToken()).isEqualTo(token);
        assertThat(leased()).isEmpty();
        Reference.reachabilityFence(loader);
    }

    @Test
    void claimedLoaderChurnReusesRetiredTokensWithoutExhaustion() throws Exception {
        for (int i = 0; i < 17_000; i++) {
            ClassLoader loader = new ClassLoader(null) {};
            int token = definitions.token(loader);
            assertThat(token).isEqualTo(1);
            Reference<?> phantom = leased().iterator().next();
            Reference<?> weak = keys().iterator().next();
            weak.clear();
            assertThat(weak.enqueue()).isTrue();
            assertThat(phantom.enqueue()).isTrue();
            Reference.reachabilityFence(loader);
        }
        ClassLoader next = new ClassLoader(null) {};
        definitions.observed(next, false);
        int token = definitions.token(next);
        assertThat(token).isEqualTo(1);
        assertThat(CodeInventory.eligibleDefinition(token)).isTrue();
        assertThat(CodeInventory.status()).containsEntry("definitionOverflow", 0L);
    }

    @Test
    void anExhaustedLoadersTokenStaysNegativeAfterSlotsFreeAndClaimsChange() throws Exception {
        for (int i = 1; i < 16_384; i++) {
            assertThat(CodeInventory.definitionToken()).isEqualTo(i);
        }
        ClassLoader exhausted = new ClassLoader(null) {};
        assertThat(definitions.token(exhausted)).isEqualTo(-1);
        CodeInventory.releaseDefinitionToken(1);
        claim();
        assertThat(definitions.token(exhausted)).isEqualTo(-1);
        assertThat(CodeInventory.snapshot(CodeInventory.currentGeneration())).containsEntry("definitionOverflow", 1L);
        assertThat(definitions.token(new ClassLoader(null) {})).isEqualTo(1);
    }

    @Test
    void aFreshDescendantWithAnEmptyIntermediateLoaderIsEligibleButAnOldDefiningLoaderIsNot() {
        ClassLoader ancestor = new ClassLoader(null) {};
        ClassLoader fresh = new ClassLoader(ancestor) {};
        ClassLoader old = new ClassLoader(null) {};
        definitions.observed(old, true);
        definitions.observed(fresh, false);

        assertThat(CodeInventory.eligibleDefinition(definitions.token(fresh))).isTrue();
        assertThat(CodeInventory.eligibleDefinition(definitions.token(old))).isFalse();
    }

    @Test
    void anEmptyAncestorCannotBecomeFreshAgainWhenOldCodeLoadsItsFirstClassAfterReload() {
        ClassLoader ancestor = new ClassLoader(null) {};
        ClassLoader child = new ClassLoader(ancestor) {};
        definitions.observed(child, false);
        definitions.token(child);
        int ancestorToken = definitions.token(ancestor);
        assertThat(CodeInventory.eligibleDefinition(ancestorToken)).isTrue();

        claim();
        definitions.observed(ancestor, false);
        assertThat(definitions.token(ancestor)).isEqualTo(ancestorToken);
        assertThat(CodeInventory.eligibleDefinition(ancestorToken)).isFalse();
    }

    @Test
    void aRetransformedLoaderAppearingAfterTheClaimSnapshotIsFreshEvenThoughAlreadyLoaded() throws Exception {
        ApplicationMethodsSensor sensor = new ApplicationMethodsSensor(null, false);
        Field enabled = ApplicationMethodsSensor.class.getDeclaredField("inventoryOn");
        enabled.setAccessible(true);
        enabled.setBoolean(sensor, true);
        Field registry = ApplicationMethodsSensor.class.getDeclaredField("inventoryDefinitions");
        registry.setAccessible(true);
        InventoryDefinitions observed = (InventoryDefinitions) registry.get(sensor);
        ClassLoader existing = new ClassLoader(null) {};
        ClassLoader gap = new ClassLoader(null) {};
        observed.observed(existing, true);

        sensor.new Tracking().onDiscovery("shop.Existing", existing, null, true);
        sensor.new Tracking().onDiscovery("shop.Gap", gap, null, true);

        assertThat(CodeInventory.eligibleDefinition(observed.token(existing))).isFalse();
        assertThat(CodeInventory.eligibleDefinition(observed.token(gap))).isTrue();
    }

    private void claim() {
        assertThat(AgentBridge.claim(
                        Map.of("application", "shop", "packages", List.of("shop"), "sensors", List.of("inventory")),
                        capture,
                        reopen))
                .containsEntry("status", AgentBridge.ARMED);
    }

    @SuppressWarnings("unchecked")
    private Set<Reference<?>> leased() throws Exception {
        return (Set<Reference<?>>) field("leased");
    }

    @SuppressWarnings("unchecked")
    private Set<Reference<?>> keys() throws Exception {
        return ((Map<Reference<?>, ?>) field("definitions")).keySet();
    }

    private Object field(String name) throws Exception {
        Field field = InventoryDefinitions.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(definitions);
    }
}
