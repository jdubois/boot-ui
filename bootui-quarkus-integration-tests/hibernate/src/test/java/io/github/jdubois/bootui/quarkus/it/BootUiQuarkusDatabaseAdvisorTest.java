package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Runs under a dedicated, empty {@link QuarkusTestProfile} so it gets its own isolated application
 * instance. {@code POST /scan} resolves every {@code Instance<DataSource>} CDI bean (including the
 * {@code @Alternative} traced {@code DataSource} that {@code BootUiSqlTraceProducer} wires ahead of the real
 * Agroal pool), which is the first thing in the whole test module to actually instantiate that bean and — as
 * a side effect — register it with the SQL Trace panel's recorder. Sharing the default-profile application
 * instance with {@code BootUiQuarkusSqlTraceOrmCaptureTest} would make that unrelated test's "the panel starts
 * unavailable" assertion order-dependent on whichever test class happens to touch the DataSource first.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusDatabaseAdvisorTest.IsolatedProfile.class)
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
        assertThat(initial.status())
                .as("GET /bootui/api/database-advisor status")
                .isEqualTo(200);
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
        assertThat(scan.status())
                .as("POST /bootui/api/database-advisor/scan status")
                .isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("evidence").path("usable").asBoolean()).isTrue();
        assertThat(scanned.path("evidence").path("coverageComplete").asBoolean(true))
                .isFalse();
        assertThat(scanned.path("evidence").path("limitations")).isNotEmpty();
        assertThat(probe().get("/bootui/api/database-advisor").json().path("evidence"))
                .isEqualTo(scanned.path("evidence"));
        assertThat(scanned.path("scan").path("status").asText())
                .as("scan status; diagnostics: %s", scanned.path("diagnostics"))
                .isEqualTo("PARTIAL");
        assertThat(scanned.path("diagnostics").findValuesAsText("message"))
                .anySatisfy(message -> assertThat(message).contains("access path", "cannot be established"));
        assertThat(scanned.path("tablesAnalyzed").asInt())
                .as("Category, Product and Tag are all read from DatabaseMetaData")
                .isGreaterThanOrEqualTo(3);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared rule registry (schema + dialect + Hibernate cross-reference rules) must have run")
                .isEqualTo(24);

        // Arc reports two DataSource beans here: the real Agroal pool and BootUI's own @Alternative SQL Trace
        // wrapper around it. Introspecting both would analyze the same physical database twice, under two
        // names, and double every finding — so the provider de-duplicates by the physical pool behind the
        // proxy and keeps the datasource's real (qualifier-derived) name.
        List<String> dataSourceNames = new ArrayList<>();
        for (JsonNode name : scanned.path("dataSourceNames")) {
            dataSourceNames.add(name.asText());
        }
        assertThat(dataSourceNames)
                .as("the wrapped and wrapping DataSource beans must resolve to one datasource")
                .containsExactly("default");
        assertThat(scanned.path("dataSources").size())
                .as("the per-datasource read status is reported for the single datasource")
                .isEqualTo(1);
        JsonNode dataSource = scanned.path("dataSources").get(0);
        assertThat(dataSource.path("name").asText()).isEqualTo("default");
        assertThat(dataSource.path("status").asText())
                .as("the H2 datasource is fully readable")
                .isEqualTo("AVAILABLE");
        assertThat(scanned.path("truncated").asBoolean())
                .as("the sample schema is far below every scan bound")
                .isFalse();

        // Rules that could not run are reported as diagnostics, never as passing checks or findings.
        assertThat(scanned.path("rulesSkipped").asInt())
                .as("the PostgreSQL and MySQL rules cannot run against H2 and must say so")
                .isGreaterThan(0);
        assertThat(scanned.path("rulesErrored").asInt())
                .as("no rule may fail to evaluate on the sample schema")
                .isZero();
        List<String> diagnosticSources = new ArrayList<>();
        for (JsonNode diagnostic : scanned.path("diagnostics")) {
            diagnosticSources.add(diagnostic.path("source").asText());
        }
        assertThat(diagnosticSources).contains("DB-PG-001", "DB-MYSQL-001");
        for (JsonNode result : scanned.path("results")) {
            assertThat(result.path("status").asText())
                    .as("only violations are listed in results")
                    .isEqualTo("VIOLATION");
        }

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/database-advisor");
        assertThat(cached.json())
                .as("the last report is cached across requests")
                .isEqualTo(scanned);
    }

    /** Empty on purpose: its only job is to give this class its own isolated Quarkus test instance. */
    public static final class IsolatedProfile implements QuarkusTestProfile {}
}
