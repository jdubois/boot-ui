package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.McpToolInfo;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus4.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus4.web.KafkaResource;
import io.github.jdubois.bootui.quarkus4.web.McpServerResource;
import io.quarkus.builder.Version;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BootUiKafkaMessagingTest {

    @RegisterExtension
    static final QuarkusExtensionTest application = new QuarkusExtensionTest()
            .withEmptyApplication()
            .setForcedDependencies(
                    List.of(Dependency.of("io.quarkus", "quarkus-messaging-kafka", Version.getVersion())))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.devservices.enabled", "false");

    @Inject
    QuarkusPanelAvailability availability;

    @Inject
    KafkaResource kafkaResource;

    @Inject
    McpServerResource mcpServer;

    @Test
    void theMessagingConnectorMakesCaptureAvailableEvenWithoutChannels() throws Exception {
        assertThat(availability.isPanelAvailable(BootUiPanels.KAFKA)).isTrue();
        var report = kafkaResource.list();
        assertThat(report.available()).isTrue();
        assertThat(report.messages()).isEmpty();
        assertThat(mcpServer.status().tools()).extracting(McpToolInfo::name).contains("get_kafka_activity");

        ClassLoader runtime = Thread.currentThread().getContextClassLoader();
        for (String name : List.of("QuarkusKafkaProducerCapture", "QuarkusKafkaConsumerCapture")) {
            Class<?> type = runtime.loadClass("io.github.jdubois.bootui.quarkus4.kafka." + name);
            assertThat(CDI.current().select(type).isResolvable()).as(name).isTrue();
        }
    }
}
