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
        assertThat(second.capture().get()).isNull();
        try (AutoCloseable scope = second.reopen().apply(null)) {
            assertThat(scope).isNotNull();
        }

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bridgeClaim() {
        return (Map<String, Object>) AgentBridge.status().get("claim");
    }
}
