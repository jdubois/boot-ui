package io.github.jdubois.bootui.quarkus4.it;

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
        assertThat(root.path("page").path("limit").asInt())
                .as("the default meter page is bounded")
                .isEqualTo(200);
        assertThat(root.path("page").path("returned").asInt())
                .as("page metadata matches the returned meter array")
                .isEqualTo(root.path("meters").size());
        assertThat(root.path("availableTypes").isArray())
                .as("type filtering choices are part of the shared contract")
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

        Response detail = probe().get("/bootui/api/metrics/detail?name=it.sample.host.requests&offset=0&limit=1");
        assertThat(detail.status()).as("GET /bootui/api/metrics/detail status").isEqualTo(200);
        assertThat(detail.json().path("totalSamples").asInt()).isEqualTo(1);
        assertThat(detail.json().path("samplePage").path("limit").asInt()).isEqualTo(1);
        assertThat(detail.json().path("samples").size()).isEqualTo(1);
        assertThat(detail.json().path("samplesTruncated").asBoolean(true)).isFalse();

        Response invalid = probe().get("/bootui/api/metrics?limit=many");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().path("error").asText()).isEqualTo("Metric limit must be between 1 and 1000");
    }

    @Test
    void metricsPanelExplainsMeterProvenanceAndFiltersOnIt() {
        registry.counter("it.provenance.orders.processed").increment();
        registry.counter("jvm.gc.pause.it").increment();

        JsonNode root = probe().get("/bootui/api/metrics?limit=1000").json();
        assertThat(root.path("catalogueVersion").asText())
                .as("the curated catalogue version travels with the report")
                .isNotBlank();

        List<String> groupIds = new ArrayList<>();
        int groupedMeters = 0;
        for (JsonNode group : root.path("groups")) {
            groupIds.add(group.path("id").asText());
            groupedMeters += group.path("meterCount").asInt();
        }
        assertThat(groupIds).as("Quarkus meters are grouped by provenance").contains("jvm", "application");
        assertThat(groupedMeters)
                .as("every matched meter belongs to exactly one group")
                .isEqualTo(root.path("page").path("matched").asInt());

        assertThat(provenanceOf(root, "jvm.gc.pause.it").path("groupId").asText())
                .as("catalogue classification works the same way on Quarkus")
                .isEqualTo("jvm");
        JsonNode application = provenanceOf(root, "it.provenance.orders.processed");
        assertThat(application.path("classified").asBoolean(true)).isFalse();
        assertThat(application.path("explanationSource").asText()).isEqualTo("UNKNOWN");
        assertThat(application.path("explanation").isNull())
                .as("BootUI does not invent an explanation for application meters")
                .isTrue();

        JsonNode filtered = probe().get("/bootui/api/metrics?limit=1000&group=jvm&provenance=classified")
                .json();
        for (JsonNode meter : filtered.path("meters")) {
            assertThat(meter.path("provenance").path("groupId").asText()).isEqualTo("jvm");
        }
        assertThat(filtered.path("meters").size()).isPositive();

        JsonNode unknown = probe().get("/bootui/api/metrics?limit=1000&explanation=UNKNOWN")
                .json();
        List<String> unknownNames = new ArrayList<>();
        for (JsonNode meter : unknown.path("meters")) {
            unknownNames.add(meter.path("name").asText());
        }
        assertThat(unknownNames).contains("it.provenance.orders.processed");

        Response invalidGroup = probe().get("/bootui/api/metrics?group=not-a-group");
        assertThat(invalidGroup.status()).isEqualTo(400);
        assertThat(invalidGroup.json().path("error").asText()).startsWith("Metric group must be one of: application");

        Response invalidProvenance = probe().get("/bootui/api/metrics?provenance=maybe");
        assertThat(invalidProvenance.status()).isEqualTo(400);
        assertThat(invalidProvenance.json().path("error").asText())
                .isEqualTo("Metric provenance must be one of: classified, unclassified");

        Response invalidExplanation = probe().get("/bootui/api/metrics?explanation=guessed");
        assertThat(invalidExplanation.status()).isEqualTo(400);
        assertThat(invalidExplanation.json().path("error").asText())
                .isEqualTo("Metric explanation source must be one of: CURATED, NATIVE, UNKNOWN");

        JsonNode detail =
                probe().get("/bootui/api/metrics/detail?name=jvm.gc.pause.it").json();
        assertThat(detail.path("provenance").path("familyId").asText()).isEqualTo("jvm.gc");
        assertThat(detail.path("provenance").path("explanationSource").asText()).isEqualTo("CURATED");
    }

    private static JsonNode provenanceOf(JsonNode report, String meterName) {
        for (JsonNode meter : report.path("meters")) {
            if (meterName.equals(meter.path("name").asText())) {
                return meter.path("provenance");
            }
        }
        throw new AssertionError("No meter " + meterName + " in the report");
    }
}
