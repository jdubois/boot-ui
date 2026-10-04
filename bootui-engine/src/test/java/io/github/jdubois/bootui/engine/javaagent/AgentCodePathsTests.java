package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapters' hooks into the code-paths sensor, bound to the bridge class from the test class path. */
class AgentCodePathsTests {

    @BeforeEach
    void install() {
        Bridges.reset();
        Bridges.StubAgent.install();
    }

    @AfterEach
    void reset() {
        AgentCodePaths.rebind();
        Bridges.reset();
    }

    @Test
    void withoutTheAgentEveryHookDoesNothing() {
        AgentCodePaths.bind(null);

        AgentCodePaths.begin();
        AgentCodePaths.phase(RequestPhase.HANDLER);
        AgentCodePaths.clearPhase();
        AgentCodePaths.end();

        assertThat(AgentCodePaths.bound()).isFalse();
    }

    @Test
    void clearingThePhaseOnAWorkerLeavesTheThreadsNextWorkWithoutOne() {
        AgentCodePaths.bind(CodePaths.class);
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "shop", "shop@1", "dev", List.of("shop"));
        claim.attach(new AgentHandoffs(() -> CorrelationContext.forRequest("00000000000000ab"), null, null));
        int serializer = CodeInventory.methodId("shop.Serializer#write()V");

        // A worker thread, after the response filter marked the response phase and the response was written.
        AgentCodePaths.phase(RequestPhase.RESPONSE);
        AgentCodePaths.clearPhase();
        CodePaths.exit(CodePaths.enter(serializer));

        List<long[]> blobs = new ArrayList<>();
        claim.drainCodePaths(blobs::add);
        assertThat(blobs)
                .singleElement()
                .satisfies(blob ->
                        assertThat(blob[CodePaths.HEADER + CodePaths.N_PHASE]).isEqualTo(CodePaths.PHASE_UNKNOWN));
        claim.disarm();
    }

    @Test
    void theHooksBoundARequestsFragmentAndMarkItsPhases() {
        AgentCodePaths.bind(CodePaths.class);
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "shop", "shop@1", "dev", List.of("shop"));
        claim.attach(new AgentHandoffs(() -> CorrelationContext.forRequest("00000000000000ab"), null, null));
        int handler = CodeInventory.methodId("shop.OrderController#place()V");

        AgentCodePaths.begin();
        AgentCodePaths.phase(RequestPhase.FILTERS);
        AgentCodePaths.phase(RequestPhase.HANDLER);
        CodePaths.exit(CodePaths.enter(handler));
        AgentCodePaths.end();

        List<long[]> blobs = new ArrayList<>();
        claim.drainCodePaths(blobs::add);
        assertThat(AgentCodePaths.bound()).isTrue();
        assertThat(blobs).singleElement().satisfies(blob -> {
            assertThat(blob[CodePaths.H_FLAGS] & CodePaths.FLAG_BEGUN).isNotZero();
            assertThat(blob[CodePaths.H_REQUEST]).isEqualTo(0xabL);
            assertThat(blob[CodePaths.HEADER + CodePaths.N_PHASE]).isEqualTo(CodePaths.PHASE_HANDLER);
        });
        assertThat(AgentCodePaths.code(RequestPhase.RESPONSE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        claim.disarm();
    }
}
