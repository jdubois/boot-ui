package io.github.jdubois.bootui.quarkus.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSetupSnippets;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentSettings;
import io.github.jdubois.bootui.quarkus.web.JavaAgentResource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuarkusAgentClaimTest {

    @BeforeEach
    void reset() {
        StubBridge.CALLS.clear();
    }

    @Test
    void theRecorderClaimsNothingWithoutTheAgent() {
        BootUiAgentRecorder recorder = new BootUiAgentRecorder();

        assertThat(recorder.claim("app", "dev", List.of("com.example"))
                        .getValue()
                        .claim())
                .as("the bridge is not on this test JVM's bootstrap class path")
                .isNull();
        assertThat(recorder.release("app", "dev").getValue().claim()).isNull();
    }

    @Test
    void theLifecycleRefinesOnStartAndDisarmsOnceOnShutdownAndDestruction() {
        AgentClaim claim = AgentClaim.claim(
                AgentBridgeAccess.bind(StubBridge.class), "app", "app@1", "test", List.of("com.example"));
        QuarkusAgentClaimLifecycle lifecycle = new QuarkusAgentClaimLifecycle(claim);

        lifecycle.onStart(null);
        lifecycle.onStop(null);
        lifecycle.disarm();

        assertThat(StubBridge.CALLS).containsExactly("claim test:app", "refine", "disarm");
        assertThat(claim.armed()).isFalse();
    }

    @Test
    void theLifecycleAttachesTheEnginesHandoffsOnStartAndDisarmingDetachesThem() {
        AgentClaim claim = AgentClaim.claim(
                AgentBridgeAccess.bind(StubBridge.class), "app", "app@1", "test", List.of("com.example"));
        AgentHandoffs handoffs = new AgentHandoffs(null, null, null);
        QuarkusAgentClaimLifecycle lifecycle = new QuarkusAgentClaimLifecycle(claim, handoffs);

        assertThat(claim.handoffs())
                .as("nothing captured before the engine is ready")
                .isNull();
        lifecycle.onStart(null);
        assertThat(claim.handoffs()).isSameAs(handoffs);
        lifecycle.onStop(null);
        assertThat(claim.handoffs()).isNull();
    }

    @Test
    void theLifecycleWithoutAClaimDoesNothing() {
        QuarkusAgentClaimLifecycle lifecycle = new QuarkusAgentClaimLifecycle((AgentClaim) null);

        lifecycle.onStart(null);
        lifecycle.onStop(null);

        assertThat(StubBridge.CALLS).isEmpty();
    }

    @Test
    void theResourceServesTheEngineReportWithQuarkusSnippets() {
        JavaAgentService service = new JavaAgentService(
                AgentBridgeAccess.absent(),
                QuarkusAgentClaim.none()::claim,
                JavaAgentSettings.of(AgentSetupSnippets.QUARKUS, true, null));

        JavaAgentReport report = new JavaAgentResource(service).report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.NOT_ATTACHED);
        assertThat(report.setup().snippets())
                .anySatisfy(snippet -> assertThat(snippet.id()).isEqualTo("quarkus-dev"));
    }

    @Test
    void productionReportsDisabled() {
        JavaAgentService service = new JavaAgentService(
                AgentBridgeAccess.absent(),
                () -> null,
                JavaAgentSettings.of(AgentSetupSnippets.QUARKUS, true, "Quarkus production mode"));

        assertThat(service.report().state()).isEqualTo(JavaAgentReport.DISABLED);
        assertThat(service.report().reason()).isEqualTo("Quarkus production mode");
    }
}
