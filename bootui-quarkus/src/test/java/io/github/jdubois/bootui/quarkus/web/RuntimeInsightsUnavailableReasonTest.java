package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.StubConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins what Runtime Insights is told about a panel this application cannot serve ({@code docs/PLAN-v2.md} §5.5): the
 * authorization evidence of an application without Quarkus security events is reported unavailable, naming
 * {@code quarkus.security.events.enabled}, rather than as evidence the run happened not to produce.
 */
class RuntimeInsightsUnavailableReasonTest {

    @Test
    void securityLogsIsUnavailableNamingThePropertyThatWouldCaptureTheEvents() {
        QuarkusPanelAvailability availability = new QuarkusPanelAvailability(StubConfig.empty());

        String reason = RuntimeInsightsResource.insightsUnavailableReason(availability, BootUiPanels.SECURITY_LOGS);

        assertThat(reason)
                .as("an application with no security events is told what would capture them")
                .isNotNull()
                .doesNotStartWith("Not available")
                .startsWith("Quarkus security events are disabled.")
                .contains("quarkus.security.events.enabled=true");
    }

    @Test
    void anExplicitlyDisabledSecurityEventsSettingIsReportedTheSameWay() {
        QuarkusPanelAvailability availability =
                new QuarkusPanelAvailability(new StubConfig(Map.of("quarkus.security.events.enabled", "false")));

        assertThat(RuntimeInsightsResource.insightsUnavailableReason(availability, BootUiPanels.SECURITY_LOGS))
                .contains("quarkus.security.events.enabled=true");
    }

    @Test
    void anAvailablePanelHasNoReason() {
        QuarkusPanelAvailability availability = new QuarkusPanelAvailability(StubConfig.empty());

        assertThat(RuntimeInsightsResource.insightsUnavailableReason(availability, BootUiPanels.RUNTIME_INSIGHTS))
                .as("Runtime Insights only distinguishes panels that are off, so an available one carries nothing")
                .isNull();
    }
}
