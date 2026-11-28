package io.github.jdubois.bootui.quarkus4fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.builder.Version;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkus4BootstrapTest {
    @TestHTTPResource("/fixture/bootstrap")
    URI endpoint;

    @Test
    void bootstrapsTheSeparateExtensionAndSerializesNestedRecordsWithJackson3() throws Exception {
        assertThat(Version.getVersion())
                .isEqualTo(
                        ConfigProvider.getConfig().getValue("bootui.fixture.expected-quarkus-version", String.class));
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response =
                    client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var state = new ObjectMapper().readTree(response.body());
            assertThat(state.path("platform").asText()).isEqualTo("quarkus");
            assertThat(state.path("items").get(0).path("name").asText()).isEqualTo("nested");
            assertThat(state.path("items").get(0).path("values").size()).isEqualTo(2);
            assertThat(state.path("items").get(0).path("values").get(1).asText())
                    .isEqualTo("two");
        }
    }
}
