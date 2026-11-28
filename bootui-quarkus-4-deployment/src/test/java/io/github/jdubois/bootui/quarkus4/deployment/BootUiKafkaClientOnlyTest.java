package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.builder.Version;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BootUiKafkaClientOnlyTest {

    @RegisterExtension
    static final QuarkusExtensionTest application = new QuarkusExtensionTest()
            .withEmptyApplication()
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-kafka-client", Version.getVersion())))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .addBuildChainCustomizer(builder -> builder.addBuildStep(context -> assertThat(
                                    context.consume(Capabilities.class).isPresent(Capability.KAFKA))
                            .as("the client-only application advertises the broad Kafka capability")
                            .isTrue())
                    .consumes(Capabilities.class)
                    .produces(ValidationErrorBuildItem.class)
                    .build());

    @TestHTTPResource
    URI baseUri;

    @Test
    void theLowLevelKafkaClientDoesNotMakeCaptureAvailable() throws Exception {
        ClassLoader runtime = Thread.currentThread().getContextClassLoader();
        assertThat(runtime.getResource("org/apache/kafka/clients/producer/KafkaProducer.class"))
                .isNotNull();
        assertThat(runtime.getResource("io/smallrye/reactive/messaging/IncomingInterceptor.class"))
                .isNull();
        assertThat(runtime.getResource("io/smallrye/reactive/messaging/kafka/KafkaConnector.class"))
                .isNull();

        var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JsonNode panels = get(client, "/bootui/api/panels").path("panels");
        JsonNode kafkaPanel = StreamSupport.stream(panels.spliterator(), false)
                .filter(panel -> "kafka".equals(panel.path("id").asString()))
                .findFirst()
                .orElseThrow();
        assertThat(kafkaPanel.path("available").asBoolean(true)).isFalse();
        assertThat(kafkaPanel.path("unavailableReason").asString()).contains("quarkus-messaging-kafka");

        JsonNode report = get(client, "/bootui/api/kafka");
        assertThat(report.path("available").asBoolean(true)).isFalse();
        assertThat(report.path("unavailableReason").asString()).isNotBlank();
        assertThat(report.path("messages").isArray()).isTrue();
        assertThat(report.path("messages").isEmpty()).isTrue();

        JsonNode tools = get(client, "/bootui/api/mcp-server").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(StreamSupport.stream(tools.spliterator(), false)
                        .map(tool -> tool.path("name").asString()))
                .doesNotContain("get_kafka_activity");
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return new ObjectMapper().readTree(response.body());
    }
}
