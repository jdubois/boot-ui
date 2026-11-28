package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.faulttolerance.InventoryClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves the one runtime signal SmallRye Fault Tolerance does expose reaches the panel: a named circuit
 * breaker's state transitions, captured through {@code CircuitBreakerMaintenance.onStateChange} and recorded
 * as a metadata-only {@code STATE_TRANSITION} event.
 *
 * <p>Uses its own breaker ({@code payment-gateway}) so tripping it cannot disturb the live-state assertions of
 * {@link BootUiQuarkusFaultToleranceTest}, which shares this application instance. Also pins the capture-only
 * contract: the failure's message must never appear in the report.</p>
 */
@QuarkusTest
class BootUiQuarkusFaultToleranceStateTransitionTest {

    @Inject
    InventoryClient inventoryClient;

    @TestHTTPResource
    URL baseUrl;

    @Test
    void trippingANamedBreakerIsCapturedAsAMetadataOnlyEvent() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                inventoryClient.pay("ORDER-" + attempt);
            } catch (RuntimeException expected) {
                // Both the simulated failure and the open-circuit rejection are expected here.
            }
        }

        JsonNode transition = awaitStateTransition();
        assertThat(transition.path("provider").asText()).isEqualTo("smallrye-fault-tolerance");
        assertThat(transition.path("policyType").asText()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(transition.path("state").asText())
                .as("the captured transition names the state the breaker moved to")
                .isEqualTo("OPEN");
        assertThat(transition.path("failureCategory").isNull())
                .as("a state transition is not a failure of its own")
                .isTrue();
    }

    private JsonNode awaitStateTransition() throws InterruptedException {
        JsonNode lastReport = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            lastReport = new BootUiHttpProbe(baseUrl.toExternalForm())
                    .get("/bootui/api/fault-tolerance")
                    .json();
            assertThat(lastReport.toString())
                    .as("the raw failure message is never recorded")
                    .doesNotContain("payment gateway unreachable");
            for (JsonNode event : lastReport.path("events")) {
                if ("payment-gateway".equals(event.path("policyName").asText(null))
                        && "STATE_TRANSITION".equals(event.path("outcome").asText(null))) {
                    return event;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No payment-gateway STATE_TRANSITION event was captured: " + lastReport);
    }
}
