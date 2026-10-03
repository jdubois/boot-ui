package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins <b>Free BootUI memory</b> ({@code POST /bootui/api/live-memory/offload}) on Quarkus: the producer finds the
 * BootUI stores ArC has already created, empties them, and reports the heap around the garbage collection request.
 * The shared conformance catalog lists the action but never invokes it, because it would empty the buffers other
 * conformance checks read.
 */
@QuarkusTest
class BootUiQuarkusLiveMemoryOffloadTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void offloadEmptiesTheCreatedBootUiStoresAndReportsTheHeap() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        // The runtime journal and the HTTP exchange buffer exist once the application has served a request.
        probe.get("/bootui/api/live-memory");

        Response response = probe.post("/bootui/api/live-memory/offload", Map.of());

        assertThat(response.status()).as("POST /live-memory/offload status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode report = response.json();
        assertThat(report.path("gcRequested").asBoolean()).isTrue();
        assertThat(report.path("explicitGcDisabled").isBoolean()).isTrue();
        assertThat(report.path("heapUsedBeforeBytes").asLong()).isPositive();
        assertThat(report.path("heapUsedAfterBytes").asLong()).isPositive();
        assertThat(report.path("reclaimedBytes").asLong()).isNotNegative();
        List<String> ids = new ArrayList<>();
        for (JsonNode store : report.path("stores")) {
            ids.add(store.path("id").asText());
            assertThat(store.path("cleared").asBoolean())
                    .as("store %s must be emptied", store.path("id").asText())
                    .isTrue();
            assertThat(store.path("failure").isNull()).isTrue();
        }
        assertThat(ids).contains("runtime-journal", "http-exchanges").doesNotHaveDuplicates();
    }
}
