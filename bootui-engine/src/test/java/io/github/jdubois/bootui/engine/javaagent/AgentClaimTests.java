package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentClaimTests {

    private AgentBridgeAccess access;
    private Bridges.StubAgent agent;

    @BeforeEach
    void install() {
        Bridges.reset();
        access = Bridges.access();
        agent = Bridges.StubAgent.install();
    }

    @AfterEach
    void reset() {
        Bridges.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClaimIsArmedRefinedAndDisarmedOnceWithItsToken() {
        AgentClaim claim = AgentClaim.claim(access, "petclinic", "petclinic@1", "dev", List.of("com.example", " "));

        assertThat(claim.claimStatus()).isEqualTo(AgentClaim.ARMED);
        assertThat(claim.armed()).isTrue();
        assertThat(claim.generation()).isPositive();
        assertThat(claim.packages()).containsExactly("com.example");
        assertThat(bridgeClaim()).containsEntry("owner", "petclinic@1").containsEntry("armed", true);

        assertThat(claim.refine(List.of("com.example.web", "org.acme"))).containsEntry("status", "armed");
        assertThat((List<Object>) bridgeClaim().get("packages"))
                .containsExactly("com.example", "com.example.web", "org.acme");

        assertThat(claim.disarm()).containsEntry("status", "disarmed");
        assertThat(claim.disarm()).as("disarming twice").containsEntry("status", "stale");
        assertThat(claim.armed()).isFalse();
        assertThat(claim.refine(List.of("late"))).containsEntry("status", "stale");
        assertThat(bridgeClaim()).containsEntry("armed", false);
        assertThat(agent.ops()).containsExactly("claim", "refine", "disarm");
    }

    @Test
    void theSameSlotReplacesItsClaimAsADevToolsRestartDoes() {
        AgentClaim first = AgentClaim.claim(access, "petclinic", "petclinic@1", "dev", List.of("com.example"));
        AgentClaim second = AgentClaim.claim(access, "petclinic", "petclinic@2", "dev", List.of("com.example"));

        assertThat(second.claimStatus()).isEqualTo(AgentClaim.ARMED);
        assertThat(bridgeClaim()).containsEntry("owner", "petclinic@2");
        assertThat(first.disarm()).as("the replaced run's token is stale").containsEntry("status", "stale");
        assertThat(bridgeClaim()).containsEntry("owner", "petclinic@2").containsEntry("armed", true);
    }

    @Test
    void aTestApplicationIsHeldWhileADevApplicationTakesItOver() {
        AgentClaim test = AgentClaim.claim(access, "tests", "tests@1", "test", List.of("com.example"));
        AgentClaim other = AgentClaim.claim(access, "other", "other@1", "test", List.of("org.other"));

        assertThat(other.claimStatus()).isEqualTo(AgentClaim.HELD);
        assertThat(other.armed()).isFalse();
        assertThat(other.disarm()).containsEntry("status", "stale");

        AgentClaim dev = AgentClaim.claim(access, "app", "app@1", "dev", List.of("com.example"));

        assertThat(dev.claimStatus()).isEqualTo(AgentClaim.ARMED);
        assertThat(AgentBridge.status().get("counters"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("takeovers", 1L)
                .containsEntry("holds", 1L);
        assertThat(test.refine(List.of())).containsEntry("status", "stale");
    }

    @Test
    void releasingWithoutATokenSparesAnotherApplicationsArmedClaim() {
        AgentClaim.claim(access, "app", "app@1", "dev", List.of("com.example"));

        assertThat(AgentClaim.release(access, "disabled", "dev")).containsEntry("status", "held");
        assertThat(bridgeClaim()).containsEntry("owner", "app@1");
        assertThat(AgentClaim.release(access, "app", "dev")).containsEntry("status", "released");
        assertThat(AgentBridge.status().get("claim")).isNull();
    }

    @Test
    void aFailingAgentFailsTheClaimWithItsReason() {
        agent.claimAnswer = Map.of("status", "failed", "reason", "boom");

        AgentClaim claim = AgentClaim.claim(access, "app", "app@1", "dev", List.of());

        assertThat(claim.claimStatus()).isEqualTo(AgentClaim.FAILED);
        assertThat(claim.result()).containsEntry("reason", "boom");
        assertThat(claim.armed()).isFalse();
        assertThat(AgentBridge.recording())
                .as("the failed claim is released, not left armed")
                .isFalse();
        assertThat(AgentBridge.status().get("claim")).isNull();
    }

    @Test
    void withoutABridgeAClaimIsUnavailableAndEveryCallIsHarmless() {
        AgentClaim claim = AgentClaim.claim(AgentBridgeAccess.absent(), "app", "app@1", "dev", List.of("x"));

        assertThat(claim.claimStatus()).isEqualTo(AgentClaim.UNAVAILABLE);
        assertThat(claim.refine(List.of("y"))).containsEntry("status", "stale");
        assertThat(claim.disarm()).containsEntry("status", "stale");
        assertThat(AgentClaim.release(null, "app", "dev")).containsEntry("status", "unavailable");
    }

    @Test
    void eachClaimPassesFreshCaptureAndReopenFunctionsThatItKeepsReachable() throws Exception {
        AgentClaim first = AgentClaim.claim(access, "app", "app@1", "dev", List.of());
        AgentClaim second = AgentClaim.claim(access, "app", "app@2", "dev", List.of());

        assertThat(first.capture()).isNotSameAs(second.capture());
        assertThat(first.reopen()).isNotSameAs(second.reopen());
        assertThat(second.capture().get()).as("no handoffs attached yet").isNull();
        assertThat(second.reopen().apply(null)).as("no handoffs attached yet").isNull();

        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertThat(bridgeClaim())
                .as("the bridge's weak references stay set while the claim is reachable")
                .containsEntry("owner", "app@2")
                .containsEntry("abandoned", false);
        assertThat(second.armed()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClaimAsksForItsSensorsAndDelegatesToTheHandoffsAttachedUntilItIsDisarmed() {
        AgentSensorSettings sensors = new AgentSensorSettings(
                List.of("executors"),
                List.of("com.acme.Wrapper"),
                List.of("worker-"),
                java.time.Duration.ofSeconds(9),
                5000);
        AgentClaim claim = AgentClaim.claim(access, "app", "app@1", "dev", List.of("com.example"), sensors);

        Map<String, Object> request = agent.requests.get(0);
        assertThat((List<String>) request.get("sensors")).containsExactly("executors");
        assertThat(request).as("rounded up to a power of two").containsEntry("ringCapacity", 8192);
        assertThat((Map<String, Object>) request.get("executors"))
                .containsEntry("skipTasks", List.of("com.acme.Wrapper"))
                .containsEntry("skipThreads", List.of("worker-"));
        assertThat(claim.sensors().maxHandoff()).isEqualTo(java.time.Duration.ofSeconds(9));

        AgentHandoffs handoffs =
                new AgentHandoffs(() -> io.github.jdubois.bootui.spi.CorrelationContext.forRequest("r1"), null, null);
        claim.attach(handoffs);
        assertThat(claim.handoffs()).isSameAs(handoffs);
        assertThat(claim.capture().get()).isInstanceOf(Object[].class);

        claim.disarm();
        assertThat(claim.handoffs()).as("disarming detaches").isNull();
        assertThat(claim.capture().get()).isNull();
        claim.attach(handoffs);
        assertThat(claim.handoffs()).as("an ended claim attaches nothing").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void
            theDefaultClaimAsksForTheExecutorsInventoryCodePathsProcessesNetworkAndBlockingSensorsWithTheDefaultSkipListsAndRing() {
        AgentClaim.claim(access, "app", "app@1", "dev", List.of());

        Map<String, Object> request = agent.requests.get(0);
        assertThat((List<String>) request.get("sensors"))
                .containsExactly(
                        "executors", "inventory", "code-paths", "processes", "network", "blocking", "resources");
        assertThat(request).containsEntry("ringCapacity", AgentSensorSettings.DEFAULT_RING_CAPACITY);
        assertThat((Map<String, Object>) request.get("executors"))
                .containsEntry("skipTasks", AgentSensorSettings.DEFAULT_SKIP_TASKS)
                .containsEntry("skipThreads", AgentSensorSettings.DEFAULT_SKIP_THREADS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void beanClassesReachTheAgentAtTheClaimAndAsAUnionAtEachRefine() {
        AgentClaim claim = AgentClaim.claim(
                access,
                "app",
                "app@1",
                "dev",
                List.of("com.example"),
                AgentSensorSettings.defaults(),
                List.of("com.example.OrderService", " com.example.OrderService "));

        assertThat((List<String>) agent.requests.get(0).get("beanClasses")).containsExactly("com.example.OrderService");
        assertThat(claim.refine(List.of(), List.of("com.example.Cart"))).containsEntry("status", "armed");
        assertThat((List<String>) agent.requests.get(1).get("beanClasses"))
                .containsExactly("com.example.OrderService", "com.example.Cart");
        assertThat(claim.refine(List.of("com.other"))).containsEntry("status", "armed");
        assertThat((List<String>) agent.requests.get(2).get("beanClasses"))
                .as("a refine without bean classes keeps them")
                .containsExactly("com.example.OrderService", "com.example.Cart");
        Map<String, Object> bridgeClaim = (Map<String, Object>) claim.result().get("claim");
        assertThat(bridgeClaim).as("status carries their count, not the names").containsEntry("beanClassCount", 2);
        assertThat(bridgeClaim).doesNotContainKey("beanClasses");
    }

    /** I4: bean classes count as instrumented only once the agent accepted them, at the claim or a refine. */
    @Test
    void beanClassesAreRecordedOnlyOnceTheAgentAcceptedThem() {
        AgentClaim first = AgentClaim.claim(
                access,
                "app",
                "app@1",
                "dev",
                List.of("com.example"),
                AgentSensorSettings.defaults(),
                List.of("com.example.OrderService"));
        assertThat(first.beanClasses()).containsExactly("com.example.OrderService");
        AgentClaim second = AgentClaim.claim(access, "app", "app@2", "dev", List.of("com.example"));

        // The replaced run's refine reaches the bridge, which refuses its stale token.
        assertThat(first.refine(List.of(), List.of("com.example.Cart"))).containsEntry("status", "stale");
        assertThat(first.beanClasses()).containsExactly("com.example.OrderService");

        AgentClaim held = AgentClaim.claim(
                access,
                "other",
                "other@1",
                "test",
                List.of("org.other"),
                AgentSensorSettings.defaults(),
                List.of("org.other.Service"));
        assertThat(held.armed()).isFalse();
        assertThat(held.beanClasses()).as("never accepted").isEmpty();
        assertThat(second.refine(List.of(), List.of("com.example.Cart"))).containsEntry("status", "armed");
        assertThat(second.beanClasses()).containsExactly("com.example.Cart");
    }

    @Test
    void theClaimOwnsOneDrainerUntilItIsDisarmed() {
        AgentClaim claim = AgentClaim.claim(access, "app", "app@1", "dev", List.of());
        AgentRecordDrainer drainer = claim.drainer();

        assertThat(claim.drainer()).isSameAs(drainer);
        drainer.route(AgentRecordDrainer.SENSOR_INVENTORY, record -> {});
        assertThat(drainer.running()).isTrue();

        claim.disarm();

        assertThat(drainer.closed()).isTrue();
        assertThat(drainer.running()).isFalse();
        assertThat(claim.drainer()).isNull();
        drainer.route(AgentRecordDrainer.SENSOR_INVENTORY, record -> {});
        assertThat(drainer.running()).as("a closed drainer never starts again").isFalse();
    }

    @Test
    void theRingCapacityIsClampedAndRoundedUpToAPowerOfTwo() {
        assertThat(AgentSensorSettings.ringCapacity(0)).isEqualTo(AgentSensorSettings.DEFAULT_RING_CAPACITY);
        assertThat(AgentSensorSettings.ringCapacity(-1)).isEqualTo(AgentSensorSettings.DEFAULT_RING_CAPACITY);
        assertThat(AgentSensorSettings.ringCapacity(10)).isEqualTo(1024);
        assertThat(AgentSensorSettings.ringCapacity(65_536)).isEqualTo(65_536);
        assertThat(AgentSensorSettings.ringCapacity(100_000)).isEqualTo(131_072);
        assertThat(AgentSensorSettings.ringCapacity(Integer.MAX_VALUE)).isEqualTo(4_194_304);
        assertThat(AgentSensorSettings.defaults().inventory()).isTrue();
        assertThat(new AgentSensorSettings(List.of("executors"), null, null, null).ringCapacity())
                .isEqualTo(AgentSensorSettings.DEFAULT_RING_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bridgeClaim() {
        return (Map<String, Object>) AgentBridge.status().get("claim");
    }
}
