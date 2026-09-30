package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/PLAN-v2.md} §5.1 on a real Quarkus REST request: a statement run by the resource method records the
 * {@code HANDLER} phase, and one run while the response entity is written records {@code RESPONSE}.
 */
@QuarkusTest
class BootUiQuarkusRequestPhaseTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void sqlRecordsThePhaseOfTheRequestItRanIn() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        BootUiHttpProbe.Response response = probe.get("/it/sql-phases");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("while-writing");

        Map<String, String> phaseBySql = new HashMap<>();
        Map<String, String> requestBySql = new HashMap<>();
        for (JsonNode entry : probe.get("/bootui/api/sql-trace").json().path("entries")) {
            String sql = entry.path("sql").asText();
            if (sql.contains("in-handler") || sql.contains("while-writing")) {
                phaseBySql.put(
                        sql.contains("in-handler") ? "handler" : "writing",
                        entry.path("requestPhase").asText());
                requestBySql.put(
                        sql.contains("in-handler") ? "handler" : "writing",
                        entry.path("requestId").asText());
            }
        }

        assertThat(phaseBySql).containsEntry("handler", "HANDLER").containsEntry("writing", "RESPONSE");
        assertThat(requestBySql.get("handler")).isNotBlank().isEqualTo(requestBySql.get("writing"));
    }
}
