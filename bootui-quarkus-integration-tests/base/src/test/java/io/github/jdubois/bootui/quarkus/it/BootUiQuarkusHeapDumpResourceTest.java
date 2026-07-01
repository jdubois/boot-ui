package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Heap Dump panel actions ({@code HeapDumpResource} over the shared
 * engine {@code HeapDumpService}). The passive {@code GET} is covered by the shared conformance suite;
 * this test pins the state-changing actions the panel's buttons invoke so they are wired end-to-end on
 * Quarkus rather than 404-ing: {@code analyze} round-trips through the engine, {@code delete} binds its
 * form parameter and returns the engine's error report (not a crash) for an unknown dump, and the raw
 * {@code download} stays disabled-by-default with a 404 because the {@code .hprof} carries unmasked
 * secrets. Same-origin loopback POSTs are accepted by the shared {@code LocalhostGuard} write floor.
 */
@QuarkusTest
class BootUiQuarkusHeapDumpResourceTest {

    private static final Map<String, String> FORM_HEADERS = Map.of("Content-Type", "application/x-www-form-urlencoded");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void analyzeRunsThroughTheEngineAndReportsAnalyzedStatus() {
        Response response = probe().post("/bootui/api/heap-dump/analyze", Map.of());

        assertThat(response.status()).as("POST /heap-dump/analyze status").isEqualTo(200);
        assertThat(response.isJson())
                .as("analyze response content-type (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("capture").path("status").asText())
                .as("analyze must drive the engine capture status to ANALYZED")
                .isEqualTo("ANALYZED");
    }

    @Test
    void deleteBindsItsFormParamAndReturnsAnErrorReportForAnUnknownDump() {
        Response response =
                probe().request("POST", "/bootui/api/heap-dump/delete", FORM_HEADERS, "name=does-not-exist.hprof");

        assertThat(response.status()).as("POST /heap-dump/delete status").isEqualTo(200);
        assertThat(response.isJson())
                .as("delete response content-type (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("capture").path("status").asText())
                .as("deleting an unknown dump must surface the engine's ERROR report, not crash")
                .isEqualTo("ERROR");
    }

    @Test
    void rawDownloadIsDisabledByDefaultAndReturns404() {
        Response response = probe().get("/bootui/api/heap-dump/download?name=anything.hprof");

        assertThat(response.status())
                .as("raw heap-dump download is disabled by default and must 404")
                .isEqualTo(404);
    }
}
