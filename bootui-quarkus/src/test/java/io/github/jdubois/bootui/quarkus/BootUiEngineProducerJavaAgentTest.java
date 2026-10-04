package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.quarkus.javaagent.BootUiAgentRecorder;
import io.github.jdubois.bootui.quarkus.javaagent.QuarkusAgentClaim;
import io.github.jdubois.bootui.quarkus.javaagent.StubBridge;
import io.quarkus.runtime.LaunchMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Java Agent panel on Quarkus reports the build-time {@code bootui.agent.enabled} the static-init claim was decided
 * with ({@code docs/PLAN-v2.md} M5-1), never a runtime value of that build-time property, which changes nothing.
 */
class BootUiEngineProducerJavaAgentTest {

    @Test
    void aBuildThatDisabledTheAgentReportsItNotEnabledWhateverTheRuntimeValue() {
        String previous = System.setProperty("bootui.agent.enabled", "true");
        try {
            QuarkusAgentClaim released =
                    new BootUiAgentRecorder().release("app", "dev").getValue();

            JavaAgentReport report = BootUiEngineProducer.javaAgentService(
                            AgentBridgeAccess.bind(StubBridge.class), released, LaunchMode.DEVELOPMENT)
                    .report();

            assertThat(released.enabled()).isFalse();
            assertThat(report.state()).isEqualTo(JavaAgentReport.DORMANT);
            assertThat(report.reason()).contains("bootui.agent.enabled is false");
        } finally {
            if (previous == null) {
                System.clearProperty("bootui.agent.enabled");
            } else {
                System.setProperty("bootui.agent.enabled", previous);
            }
        }
    }

    @Test
    void aBuildThatEnabledTheAgentReportsItEnabledWhateverTheRuntimeValue() {
        String previous = System.setProperty("bootui.agent.enabled", "false");
        try {
            QuarkusAgentClaim unclaimed = new BootUiAgentRecorder()
                    .claim("app", "dev", List.of("com.example"))
                    .getValue();

            JavaAgentReport report = BootUiEngineProducer.javaAgentService(
                            AgentBridgeAccess.bind(StubBridge.class), unclaimed, LaunchMode.DEVELOPMENT)
                    .report();

            assertThat(unclaimed.enabled()).isTrue();
            assertThat(report.state()).isEqualTo(JavaAgentReport.DORMANT);
            assertThat(report.reason()).doesNotContain("bootui.agent.enabled is false");
        } finally {
            if (previous == null) {
                System.clearProperty("bootui.agent.enabled");
            } else {
                System.setProperty("bootui.agent.enabled", previous);
            }
        }
    }
}
