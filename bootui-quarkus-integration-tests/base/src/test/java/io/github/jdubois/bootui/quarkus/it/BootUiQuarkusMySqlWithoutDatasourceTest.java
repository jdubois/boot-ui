package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Augments with no JDBC extension or vendor driver: the service and endpoint remain linkage-safe. */
@QuarkusTest
class BootUiQuarkusMySqlWithoutDatasourceTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Test
    void noDatasourceIsUnavailableAndExplicitReadPublishesAnEmptyDisabledReport() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        JsonNode mysql = null;
        for (JsonNode panel : probe.get("/bootui/api/panels").json().path("panels")) {
            if ("mysql".equals(panel.path("id").asText())) {
                mysql = panel;
            }
        }
        assertThat(mysql).isNotNull();
        assertThat(mysql.path("available").asBoolean(true)).isFalse();
        assertThat(mysql.path("unavailableReason").asText()).contains("MySQL JDBC datasource");

        var initial = probe.get("/bootui/api/mysql");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("status").asText()).isEqualTo("NOT_READ");
        assertThat(initial.json().path("readStartedAt").isNull()).isTrue();
        assertThat(initial.json().path("readAt").isNull()).isTrue();
        var read = probe.post("/bootui/api/mysql/read", JSON);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("status").asText()).isEqualTo("DISABLED");
        assertThat(read.json().path("localOnly").asBoolean()).isTrue();
        assertThat(read.json().path("dataSourcesRead").asInt(-1)).isZero();
        assertThat(read.json().path("dataSources").isArray()).isTrue();
        assertThat(read.json().path("dataSources")).isEmpty();
        assertThat(probe.get("/bootui/api/mysql").json()).isEqualTo(read.json());
    }

    @Test
    void absentCapabilityNeverAdvertisesMysqlMcpTools() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.request("POST", "/bootui/api/mcp-server/toggle", JSON, "{\"enabled\":true}")
                        .status())
                .isEqualTo(200);
        var tools = probe.request(
                "POST", "/bootui/api/mcp", JSON, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(tools.status()).isEqualTo(200);
        assertThat(tools.json().path("result").path("tools").toString())
                .doesNotContain("\"mysql_read\"", "\"get_mysql_report\"");
    }
}
