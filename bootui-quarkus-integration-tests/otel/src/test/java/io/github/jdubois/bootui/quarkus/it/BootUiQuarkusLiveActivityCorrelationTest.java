package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Live Activity correlation path end to end, with <em>real</em> OpenTelemetry context
 * propagation. Hitting {@code /it/sql} (a {@code @Blocking} endpoint, so the JDBC runs on a worker thread)
 * produces an HTTP request and a SQL statement on two different threads; the BootUI extension stamps the
 * active server span's trace id at each capture point ({@code QuarkusHttpExchangeCaptureFilter} on the event
 * loop, {@code SqlTracingProxies} → {@code SqlTraceRecorder} on the worker thread via
 * {@code QuarkusOtelTraceIdProvider}). The engine {@code LiveActivityAssembler} then nests the SQL entry
 * under the request entry sharing that trace id.
 *
 * <p>This is the OpenTelemetry-<em>present</em> proof that the trace id actually survives the
 * event-loop→worker hop (the crux of the design); the engine {@code LiveActivityAssemblerTests} pin the
 * pure nesting algorithm, and the sibling {@code bootui-quarkus-integration-tests} module proves the
 * OTel-<em>absent</em> feed stays flat. Self {@code /bootui} traffic is filtered on capture, so the activity
 * {@code GET} itself never appears.</p>
 */
@QuarkusTest
class BootUiQuarkusLiveActivityCorrelationTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void sqlNestsUnderItsOwningRequestViaSharedTraceId() {
        Response probeCall = probe().get("/it/sql");
        assertThat(probeCall.status())
                .as("the SQL probe endpoint must answer 200")
                .isEqualTo(200);
        assertThat(probeCall.body()).as("the probe must run its query").isEqualTo("ok:42");

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);

        JsonNode entries = activity.json().path("entries");
        assertThat(entries.isArray()).as("the activity feed must carry entries").isTrue();

        JsonNode request = null;
        for (JsonNode entry : entries) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && "/it/sql".equals(entry.path("path").asText())) {
                request = entry;
            }
        }
        assertThat(request)
                .as("the /it/sql call must surface as a REQUEST entry")
                .isNotNull();
        String requestId = request.path("id").asText(null);
        String requestTrace = request.path("correlationId").asText(null);
        assertThat(requestTrace)
                .as("with OpenTelemetry present the request entry must carry the server span's trace id")
                .isNotBlank();

        JsonNode sql = null;
        for (JsonNode entry : entries) {
            if ("SQL".equals(entry.path("type").asText())) {
                sql = entry;
            }
        }
        assertThat(sql)
                .as("the SELECT issued by /it/sql must surface as a SQL entry")
                .isNotNull();
        assertThat(sql.path("correlationId").asText(null))
                .as("the SQL entry must carry the same trace id as its request (propagated across the "
                        + "event-loop→worker hop)")
                .isEqualTo(requestTrace);
        assertThat(sql.path("parentId").asText(null))
                .as("the engine must nest the SQL entry under its owning request via the shared trace id")
                .isEqualTo(requestId);

        assertThat(request.path("profileable").asBoolean(true))
                .as("profileable stays false on Quarkus (no per-request profile drill-down endpoint)")
                .isFalse();
    }
}
