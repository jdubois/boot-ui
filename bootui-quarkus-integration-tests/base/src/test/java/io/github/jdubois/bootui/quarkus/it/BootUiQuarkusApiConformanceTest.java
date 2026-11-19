package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Quarkus adapter, by booting this minimal (Docker-free) Quarkus app under {@code @QuarkusTest}.
 *
 * <p>This is the Quarkus mirror of the Spring sample app's {@code SpringApiConformanceTest}: both
 * adapters answer the exact same {@code /bootui/api/**} contract, so the shared Vue UI binds to one
 * stable shape. {@code @QuarkusTest} boots in {@code LaunchMode.TEST}, so the console's build steps are
 * registered (they are gated off only in production) and the engine-backed endpoints are live.</p>
 *
 * <p>The dedicated test profile sets {@code bootui.panels.copilot.enabled=false} to enable
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody}; {@code
 * bootui.panels.heap-dump.read-only=true} enables
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}. The profile
 * disables heap-dump capture and real Claude Code session discovery, and isolates conformance mutations
 * (logger level, cached scan report, telemetry clear) from the adapter's other {@code @QuarkusTest}
 * classes. A profile-only plain H2 datasource exercises the persistence confirmation gate and gives the
 * Database Advisor one real, retained keyed finding. No datasource extension is added, so the other base
 * tests still exercise the dependency-absent paths. Flyway actions and SQL Trace insights run their exact
 * shared assertions in the existing Flyway and datasource capability modules.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusApiConformanceTest.ConformanceProfile.class)
class BootUiQuarkusApiConformanceTest extends AbstractBootUiApiConformanceTest {

    public static class ConformanceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.overrides-file", "target/api-conformance/application-bootui.properties",
                    "bootui.panels.copilot.enabled", "false",
                    "bootui.panels.heap-dump.read-only", "true",
                    "bootui.heap-dump.capture-enabled", "false",
                    "bootui.claude-code.enabled", "OFF",
                    "bootui.conformance.api-token", "conformance-raw-secret-value");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ConformanceDataSource.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class ConformanceDataSource {

        @Produces
        @Singleton
        DataSource dataSource() throws SQLException {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:api-conformance;DB_CLOSE_DELAY=-1");
            try (Connection connection = source.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS conformance_missing_primary_key (label VARCHAR(64))");
            }
            return source;
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Inject
    TelemetryStore telemetryStore;

    @Inject
    DataSource dataSource;

    @Test
    void datasourceFixtureMakesTheOptionalSharedContractsMandatory() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        }
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl());
        JsonNode persistence = probe.get("/bootui/api/activity").json().path("persistenceOption");
        assertThat(persistence.path("dataSourceAvailable").asBoolean()).isTrue();
        assertThat(persistence.path("active").asBoolean()).isFalse();

        var scanned = probe.post("/bootui/api/database-advisor/scan", Map.of("Content-Type", "application/json"));
        assertThat(scanned.status()).isEqualTo(200);
        String scanId = scanned.json().path("violationDetails").path("scanId").asText();
        assertThat(scanId).isNotBlank();
        JsonNode finding = probe.get("/bootui/api/database-advisor/rules/DB-SCHEMA-001/violations?scanId=" + scanId)
                .json();
        assertThat(finding.path("violationCount").asInt()).isEqualTo(1);
        assertThat(finding.path("identities")).hasSize(1);
        assertThat(finding.path("identities").get(0).asText()).startsWith("v1:");
        assertThat(finding.path("violations").get(0).asText()).contains("CONFORMANCE_MISSING_PRIMARY_KEY");
    }

    @Override
    protected TelemetryStore telemetryStore() {
        return telemetryStore;
    }

    @Override
    protected boolean expectsResolvedSourcePaths() {
        return true;
    }

    /**
     * {@code ItCatalogueExceptionMapper} is declared in {@code com.example.bootui.it.errors} rather than
     * alongside this test: BootUI excludes its own {@code io.github.jdubois.bootui.quarkus} package from
     * every declaration scan, and this integration-test application lives inside that package, so a
     * fixture declared here would be filtered out exactly like BootUI's own resources.
     */
    @Override
    protected Set<String> expectedErrorContractComponents() {
        return Set.of("ItCatalogueExceptionMapper");
    }

    @Override
    protected String baseUrl() {
        return baseUrl.toExternalForm();
    }

    @Override
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-quarkus.json";
    }

    @Override
    protected Runtime runtime() {
        return Runtime.QUARKUS;
    }

    @Override
    protected Set<String> actionlessPanels() {
        return Set.of("config");
    }

    @Override
    protected String exceptionProbePath() {
        return "/it/mapped-boom";
    }
}
