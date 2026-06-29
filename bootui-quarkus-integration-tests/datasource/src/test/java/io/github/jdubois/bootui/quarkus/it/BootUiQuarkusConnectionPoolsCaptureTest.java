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
 * Proves the Quarkus Database Connection Pools panel light-up end to end on an app that
 * <strong>does</strong> have a JDBC datasource ({@code quarkus-jdbc-h2}, backed by Agroal) on its classpath:
 * the live Agroal pool configuration and {@code AgroalDataSourceMetrics} read by
 * {@code QuarkusAgroalConnectionPoolProvider} are mapped into the shared {@code HikariPool*} wire contract by
 * the engine {@code ConnectionPoolService}, the JDBC URL / username are masked through the shared
 * {@code SecretMasker}, and the panel is surfaced on
 * {@code GET /bootui/api/database-connection-pools/pools} (plus the per-pool {@code /snapshot} route) — all
 * in-process, no Docker.
 *
 * <p>This is the datasource-<em>present</em> half of the coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the datasource-<em>absent</em> path (the AGROAL
 * capability gate keeps the panel unavailable, with no {@code ConnectionPoolProvider} bean and no
 * {@code ClassNotFoundException}). The panel is strictly read-only — nothing here triggers a mutation or a
 * network call.</p>
 */
@QuarkusTest
class BootUiQuarkusConnectionPoolsCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void poolsPanelMapsAgroalPoolWithMaskedCredentials() {
        Response report = probe().get("/bootui/api/database-connection-pools/pools");
        assertThat(report.status())
                .as("GET /bootui/api/database-connection-pools/pools status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/database-connection-pools/pools content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("hikariPresent").asBoolean(false))
                .as("the report is present when a JDBC datasource is configured")
                .isTrue();
        assertThat(root.path("total").asInt(-1))
                .as("one pool (the default datasource)")
                .isEqualTo(1);

        JsonNode pool = root.path("pools").get(0);
        assertThat(pool.path("poolName").asText(null))
                .as("the default datasource renders as 'default'")
                .isEqualTo("default");
        assertThat(pool.path("beanName").asText(null)).isEqualTo("default");

        // Agroal→Hikari config mapping.
        assertThat(pool.path("minimumIdle").asInt(-1))
                .as("min-size→minimumIdle")
                .isEqualTo(2);
        assertThat(pool.path("maximumPoolSize").asInt(-1))
                .as("max-size→maximumPoolSize")
                .isEqualTo(8);
        assertThat(pool.path("validationTimeoutMs").asLong(0))
                .as("Agroal has no per-call validation timeout → -1")
                .isEqualTo(-1L);
        assertThat(pool.path("keepaliveTimeMs").asLong(0))
                .as("Agroal has no keepalive interval → -1")
                .isEqualTo(-1L);

        // Credential masking (default exposure MASKED + maskSecrets true).
        assertThat(pool.path("jdbcUrl").asText(""))
                .as("the JDBC URL is reported")
                .contains("jdbc:h2:mem:bootui");
        assertThat(pool.path("username").asText(null))
                .as("the pool username is masked")
                .isEqualTo("******");

        // Metrics are enabled, so the live snapshot is present and internally consistent.
        assertThat(pool.path("available").asBoolean(false))
                .as("metrics enabled → pool available")
                .isTrue();
        JsonNode snapshot = pool.path("snapshot");
        assertThat(snapshot.isObject()).as("a live snapshot is present").isTrue();
        int active = snapshot.path("active").asInt(-1);
        int idle = snapshot.path("idle").asInt(-1);
        int total = snapshot.path("total").asInt(-1);
        assertThat(active).as("active count is non-negative").isGreaterThanOrEqualTo(0);
        assertThat(idle).as("idle count is non-negative").isGreaterThanOrEqualTo(0);
        assertThat(total)
                .as("total = active + idle (Agroal activeCount + availableCount)")
                .isEqualTo(active + idle);

        // The per-pool snapshot route resolves by the rendered pool name.
        Response snapshotResponse = probe().get("/bootui/api/database-connection-pools/pools/default/snapshot");
        assertThat(snapshotResponse.status())
                .as("GET .../pools/default/snapshot status")
                .isEqualTo(200);
        assertThat(snapshotResponse.json().path("total").asInt(-1)).isEqualTo(total);
    }

    @Test
    void panelManifestReportsConnectionPoolsAvailableOnQuarkus() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode root = panels.json();
        assertThat(root.path("platform").asText(null))
                .as("the manifest carries the quarkus platform discriminator")
                .isEqualTo("quarkus");

        JsonNode panel = findPanel(root, "database-connection-pools");
        assertThat(panel).as("the Database Connection Pools panel is listed").isNotNull();
        assertThat(panel.path("available").asBoolean(false))
                .as("the panel is available when a JDBC datasource is present")
                .isTrue();
    }

    private static JsonNode findPanel(JsonNode root, String id) {
        for (JsonNode panel : root.path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        return null;
    }
}
