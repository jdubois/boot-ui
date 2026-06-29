package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.cache.CacheResult;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Cache panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-cache} (Caffeine) on its classpath: the live cache topology read from the application's
 * {@code io.quarkus.cache.CacheManager} by {@code QuarkusCacheProvider} is shaped by the shared engine
 * {@code CacheService}, the Caffeine size + Micrometer cache metrics are overlaid, and the panel is surfaced on
 * {@code GET /bootui/api/spring-cache} with a working {@code POST /clear} action — all in-process, no Docker.
 *
 * <p>This is the cache-<em>present</em> half of the Cache coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the cache-<em>absent</em> path (the capability gate
 * keeps the {@code spring-cache} panel unavailable, with no {@code CacheProvider} bean). Nothing here triggers
 * a network call or scan on render — only the explicit {@code POST /clear} mutates state.</p>
 */
@QuarkusTest
class BootUiQuarkusCacheCaptureTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Inject
    OrdersService orders;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void cachePanelReportsCachesMetricsAndClears() {
        // Populate the "orders" cache: two distinct keys (one re-read to register a hit), so the Caffeine
        // cache holds two entries and the Micrometer cache meters record gets/puts.
        assertThat(orders.lookup("a")).isEqualTo("order-a");
        assertThat(orders.lookup("a")).isEqualTo("order-a");
        assertThat(orders.lookup("b")).isEqualTo("order-b");

        Response report = probe().get("/bootui/api/spring-cache");
        assertThat(report.status()).as("GET /bootui/api/spring-cache status").isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/spring-cache content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("cacheAvailable").asBoolean(false))
                .as("with quarkus-cache present the report is available")
                .isTrue();

        JsonNode ordersCache = findCache(root, "orders");
        assertThat(ordersCache)
                .as("the 'orders' cache is listed under a manager")
                .isNotNull();
        assertThat(ordersCache.path("size").asLong(-1))
                .as("the Caffeine cache size reflects the two cached keys")
                .isEqualTo(2);
        assertThat(ordersCache.path("metrics").path("available").asBoolean(false))
                .as("Micrometer cache metrics are overlaid for the 'orders' cache")
                .isTrue();

        // The clear action (state-changing, behind the shared LocalhostGuard write floor) evicts every cache.
        Response clear = probe().request(
                        "POST", "/bootui/api/spring-cache/clear", JSON_HEADERS, "{\"all\":true,\"confirm\":true}");
        assertThat(clear.status()).as("POST /clear status").isEqualTo(200);
        JsonNode clearBody = clear.json();
        assertThat(clearBody.path("status").asText()).as("clear result status").isEqualTo("cleared");
        assertThat(clearBody.path("clearedCaches").asInt(0))
                .as("at least the 'orders' cache was cleared")
                .isGreaterThanOrEqualTo(1);

        // After clearing, the cache is empty again (no surprise repopulation on render).
        JsonNode afterClear = findCache(probe().get("/bootui/api/spring-cache").json(), "orders");
        assertThat(afterClear)
                .as("the 'orders' cache is still listed after clearing")
                .isNotNull();
        assertThat(afterClear.path("size").asLong(-1))
                .as("the cleared cache reports zero entries")
                .isEqualTo(0);
    }

    private static JsonNode findCache(JsonNode root, String cacheName) {
        for (JsonNode manager : root.path("managers")) {
            for (JsonNode cache : manager.path("caches")) {
                if (cacheName.equals(cache.path("name").asText(null))) {
                    return cache;
                }
            }
        }
        return null;
    }

    @ApplicationScoped
    static class OrdersService {

        @CacheResult(cacheName = "orders")
        public String lookup(String id) {
            return "order-" + id;
        }
    }
}
