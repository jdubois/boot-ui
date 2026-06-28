package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Metrics panel light-up end to end on an app that <strong>does</strong> have a
 * {@code quarkus-micrometer} registry on its classpath: meters registered on the live composite
 * {@link MeterRegistry} are read in-process by the shared engine {@code MetricsReportProvider} (resolved
 * through the {@code Instance<MeterRegistry>} handle of {@code BootUiEngineProducer}), grouped and mapped onto
 * the neutral {@code MetricsReport}, and surfaced on {@code GET /bootui/api/metrics} — with no HTTP round trip
 * to a scrape endpoint.
 *
 * <p>This is the Micrometer-<em>present</em> half of the Metrics coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the Micrometer-<em>absent</em> path (the panel is
 * still available and reports unavailable, with no {@code MeterRegistry} bean). Beyond availability, this test
 * pins the design wrinkle of the slice: the shared engine {@code MeterSelfFilter} hides meters describing
 * BootUI's own {@code /bootui/**} traffic — both a deterministic self-tagged counter and the real
 * {@code http.server.requests} meter recorded for a self-probe of {@code /bootui/api/overview} — exactly as the
 * Spring adapter feeds {@code BootUiSelfDataFilter::shouldIncludeMeter}.</p>
 */
@QuarkusTest
class BootUiQuarkusMetricsCaptureTest {

    private static final Set<String> PATH_TAG_KEYS = Set.of(
            "uri", "path", "endpoint", "http.route", "http.target", "http.path", "http.url", "url.path", "url.full");

    @TestHTTPResource
    URL baseUrl;

    @Inject
    MeterRegistry registry;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void metricsPanelReportsHostMetersAndHidesBootUiTraffic() {
        // A host meter (visible) and a self meter (hidden by its /bootui path tag), registered on the same
        // composite registry the engine reads. The names are tag-neutral on purpose: the self-filter keys off
        // the path TAG, never the meter name.
        registry.counter("it.sample.host.requests", "uri", "/api/orders").increment(3);
        registry.counter("it.sample.self.requests", "uri", "/bootui/api/secret").increment(5);

        // A real self-probe so the Micrometer HTTP binding records an http.server.requests{uri=/bootui/...}
        // meter, exercising the self-filter against a genuine binding meter (not only the synthetic one above).
        assertThat(probe().get("/bootui/api/overview").status())
                .as("self-probe of /bootui/api/overview")
                .isEqualTo(200);

        Response response = probe().get("/bootui/api/metrics");
        assertThat(response.status()).as("GET /bootui/api/metrics status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/metrics content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("metricsAvailable").asBoolean(false))
                .as("with a Micrometer registry present the report is available")
                .isTrue();

        List<String> meterNames = new ArrayList<>();
        for (JsonNode meter : root.path("meters")) {
            meterNames.add(meter.path("name").asText(null));
        }
        assertThat(meterNames).as("the host meter is reported").contains("it.sample.host.requests");
        assertThat(meterNames)
                .as("the self-tagged meter (uri=/bootui/...) is hidden by the engine MeterSelfFilter")
                .doesNotContain("it.sample.self.requests");

        // End-to-end self-filter guarantee: no reported meter carries a path tag pointing at /bootui — this also
        // covers the real http.server.requests meter recorded for the /bootui/api/overview self-probe above.
        List<String> leakedSelfPaths = new ArrayList<>();
        for (JsonNode meter : root.path("meters")) {
            for (JsonNode tag : meter.path("availableTags")) {
                if (!PATH_TAG_KEYS.contains(tag.path("key").asText(""))) {
                    continue;
                }
                for (JsonNode value : tag.path("values")) {
                    if (value.asText("").startsWith("/bootui")) {
                        leakedSelfPaths.add(meter.path("name").asText(null) + " -> " + value.asText());
                    }
                }
            }
        }
        assertThat(leakedSelfPaths)
                .as("no reported meter may expose a /bootui path tag (BootUI's own traffic must stay hidden)")
                .isEmpty();
    }
}
