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
 * Real-boot checks for the Quarkus Database Advisor on an application that has both a real JDBC
 * {@code DataSource} (in-memory H2, added by this module for Hibernate ORM) and {@code quarkus-hibernate-orm}.
 * It is the Quarkus counterpart to {@code BootUiSpringDatabaseAdvisorTest} and pins the Quarkus-specific
 * end-to-end pipeline: the panel is unconditionally available (no capability gate — {@code javax.sql.DataSource}
 * is core JDK), {@code POST /scan} introspects the physical H2 schema through plain JDBC
 * {@code DatabaseMetaData} via the positional {@code QuarkusDatabaseAdvisorDataSourceProvider}, and — because
 * {@code quarkus-hibernate-orm} is also present here — the Hibernate cross-reference rules run (not skipped)
 * against the same {@code EntityDiscoverySource} the Hibernate advisor uses, reusing the shared engine rule
 * registry unmodified.
 */
@QuarkusTest
class BootUiQuarkusDatabaseAdvisorTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void databaseAdvisorPanelIsAlwaysAvailable() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        boolean databaseAdvisorAvailable = false;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("database-advisor".equals(panel.path("id").asText(null))) {
                databaseAdvisorAvailable = panel.path("available").asBoolean(false);
            }
        }
        assertThat(databaseAdvisorAvailable)
                .as("the Database Advisor panel is unconditionally available — javax.sql.DataSource is core JDK")
                .isTrue();
    }

    @Test
    void scanIntrospectsThePhysicalSchemaAndRunsTheHibernateCrossReferenceRules() {
        // A GET before any scan returns the local-only "not scanned" report.
        Response initial = probe().get("/bootui/api/database-advisor");
        assertThat(initial.status()).as("GET /bootui/api/database-advisor status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/database-advisor content-type (%s)", initial.contentType())
                .isTrue();
        assertThat(initial.json().path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initial.json().path("scan").path("status").asText())
                .as("a GET before POST /scan reports NOT_SCANNED")
                .isEqualTo("NOT_SCANNED");

        // POST /scan introspects the physical H2 schema (Category/Product/Tag, DDL'd by Hibernate's
        // drop-and-create strategy) and cross-references it against the mapped entities.
        Response scan = probe().post("/bootui/api/database-advisor/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/database-advisor/scan status").isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("after POST /scan against a real H2 datasource the report must be SCANNED")
                .isEqualTo("SCANNED");
        assertThat(scanned.path("tablesAnalyzed").asInt())
                .as("Category, Product and Tag are all read from DatabaseMetaData")
                .isGreaterThanOrEqualTo(3);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared rule registry (schema + dialect + Hibernate cross-reference rules) must have run")
                .isGreaterThanOrEqualTo(8);

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/database-advisor");
        assertThat(cached.json().path("scan").path("status").asText())
                .as("the last report is cached across requests")
                .isEqualTo("SCANNED");
    }
}
