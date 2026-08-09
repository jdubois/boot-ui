package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the Database Advisor's behavior on a Quarkus app that has <strong>no</strong> JDBC datasource on its
 * classpath (this base integration-test module deliberately omits one).
 *
 * <p>Unlike the Hibernate advisor (gated behind {@code @ConditionalOnClass}/a capability exclusion) or the
 * Database Connection Pools panel (gated on {@code Capability.AGROAL}), the Database Advisor's only hard
 * dependency is {@code javax.sql.DataSource}, which is core JDK — so it is wired <strong>unconditionally</strong>
 * and reported <em>available</em> in the manifest even with no datasource present. This proves the fail-closed
 * report-shape guarantee instead: {@code GET /bootui/api/database-advisor} answers with valid JSON reporting
 * {@code NOT_SCANNED} before any scan, and {@code POST /scan} renders a stable, empty {@code DISABLED} report
 * (not an error) when {@code QuarkusDatabaseAdvisorDataSourceProvider} finds no {@code DataSource} bean at all —
 * the datasource-present light-up path lives in the JDK-gated
 * {@code bootui-quarkus-hibernate-integration-tests} module (which also happens to add an H2 datasource).</p>
 */
@QuarkusTest
class BootUiQuarkusDatabaseAdvisorResourceWithoutDatasourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void databaseAdvisorPanelIsAvailableEvenWithoutADatasource() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode databaseAdvisor = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("database-advisor".equals(panel.path("id").asText(null))) {
                databaseAdvisor = panel;
            }
        }
        assertThat(databaseAdvisor)
                .as("the Database Advisor panel is present in the manifest")
                .isNotNull();
        assertThat(databaseAdvisor.path("available").asBoolean(false))
                .as("the panel is unconditionally available — javax.sql.DataSource is core JDK, no capability gate")
                .isTrue();
    }

    @Test
    void databaseAdvisorRendersNotScannedThenDisabledWithoutADataSource() {
        // A GET before any scan returns the local-only "not scanned" report; the scanner is wired even though
        // no DataSource bean exists.
        Response initial = probe().get("/bootui/api/database-advisor");
        assertThat(initial.status())
                .as("GET /bootui/api/database-advisor status")
                .isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/database-advisor content-type (%s)", initial.contentType())
                .isTrue();
        JsonNode initialBody = initial.json();
        assertThat(initialBody.path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initialBody.path("scan").path("status").asText())
                .as("a GET before POST /scan reports NOT_SCANNED, not DISABLED")
                .isEqualTo("NOT_SCANNED");

        // POST /scan with no DataSource bean present renders DISABLED (not an error) — the panel degrades
        // gracefully instead of failing the request or attempting a connection that cannot exist.
        Response scan = probe().post("/bootui/api/database-advisor/scan", JSON_HEADERS);
        assertThat(scan.status())
                .as("POST /bootui/api/database-advisor/scan status")
                .isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("with no DataSource bean the scan reports DISABLED")
                .isEqualTo("DISABLED");
        assertThat(scanned.path("tablesAnalyzed").asInt())
                .as("no tables are analysed without a DataSource")
                .isEqualTo(0);
        assertThat(scanned.path("dataSourceNames").isArray())
                .as("dataSourceNames must still be a (empty) JSON array, not null")
                .isTrue();
        assertThat(scanned.path("dataSourceNames")).isEmpty();
    }
}
