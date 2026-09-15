package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The ordinary datasource suite remains Docker-free and does not link Connector/J or Testcontainers. */
@QuarkusTest
class BootUiQuarkusMySqlH2Test {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void h2DoesNotAdvertiseMysqlOrBecomeASuccessfulMysqlObservation() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        JsonNode mysql = null;
        for (JsonNode panel : probe.get("/bootui/api/panels").json().path("panels")) {
            if ("mysql".equals(panel.path("id").asText())) {
                mysql = panel;
            }
        }
        assertThat(mysql).isNotNull();
        assertThat(mysql.path("available").asBoolean(true)).isFalse();
        var read = probe.post("/bootui/api/mysql/read", Map.of("Content-Type", "application/json"));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("dataSourcesRead").asInt(-1)).isZero();
        assertThat(read.json().path("status").asText()).isIn("DISABLED", "ERROR");
        assertThat(probe.get("/bootui/api/mysql").json()).isEqualTo(read.json());
    }
}
