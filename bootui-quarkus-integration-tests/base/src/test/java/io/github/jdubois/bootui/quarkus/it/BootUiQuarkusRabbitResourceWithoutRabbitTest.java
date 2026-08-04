package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves that the RabbitMQ capture importers fail closed when {@code quarkus-messaging-rabbitmq} is
 * absent: Quarkus still boots, the neutral report renders unavailable, and the optional messaging
 * metadata API is not accidentally pulled onto the application classpath.
 */
@QuarkusTest
class BootUiQuarkusRabbitResourceWithoutRabbitTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void rabbitPanelAndReportAreUnavailableWithAnExtensionHint() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).isEqualTo(200);

        JsonNode rabbitPanel = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("rabbitmq".equals(panel.path("id").asText(null))) {
                rabbitPanel = panel;
            }
        }
        assertThat(rabbitPanel).isNotNull();
        assertThat(rabbitPanel.path("available").asBoolean(true)).isFalse();
        assertThat(rabbitPanel.path("unavailableReason").asText()).contains("quarkus-messaging-rabbitmq");

        Response report = probe().get("/bootui/api/rabbitmq");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.json().path("available").asBoolean(true)).isFalse();
        assertThat(report.json().path("unavailableReason").asText()).contains("quarkus-messaging-rabbitmq");
    }

    @Test
    void optionalRabbitApiIsAbsent() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        assertThatThrownBy(() -> Class.forName(
                        "io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata", false, classLoader))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
