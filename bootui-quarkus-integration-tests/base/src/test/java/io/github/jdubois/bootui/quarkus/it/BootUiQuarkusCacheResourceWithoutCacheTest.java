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
 * Pins the Cache panel's behavior on a Quarkus app that does <strong>not</strong> have {@code quarkus-cache} on
 * its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the cache-<em>absent</em> half of the Cache coverage (the cache-present light-up path lives in the
 * dedicated {@code bootui-quarkus-cache-integration-tests} module). It proves the R2 capability gate fails
 * closed: the {@code io.quarkus.cache.*}-importing {@code BootUiCacheProducer} is excluded by the deployment
 * build step when {@code Capability.CACHE} is absent, so no {@code CacheProvider} bean exists and the
 * {@code cache} panel is reported <em>unavailable</em> in the manifest with an honest capability hint —
 * while the cache-API-free engine {@code CacheService} is still wired, so {@code GET /bootui/api/cache}
 * answers with valid JSON reporting {@code cacheAvailable:false} (no {@code NoClassDefFoundError} from the
 * absent backend).</p>
 */
@QuarkusTest
class BootUiQuarkusCacheResourceWithoutCacheTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void cachePanelIsUnavailableWithACapabilityHintWithoutQuarkusCache() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode cache = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("cache".equals(panel.path("id").asText(null))) {
                cache = panel;
            }
        }
        assertThat(cache).as("the Cache panel is present in the manifest").isNotNull();
        assertThat(cache.path("available").asBoolean(true))
                .as("the Cache panel is unavailable when quarkus-cache is absent")
                .isFalse();
        assertThat(cache.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-cache");
    }

    @Test
    void cacheReportRendersUnavailableWithoutQuarkusCache() {
        Response report = probe().get("/bootui/api/cache");
        assertThat(report.status()).as("GET /bootui/api/cache status").isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/cache content-type (%s)", report.contentType())
                .isTrue();
        assertThat(report.json().path("cacheAvailable").asBoolean(true))
                .as("the report is unavailable when no CacheProvider bean is wired")
                .isFalse();
    }
}
