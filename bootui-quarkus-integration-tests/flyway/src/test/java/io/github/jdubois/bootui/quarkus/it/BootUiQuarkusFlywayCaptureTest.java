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
 * Proves the Quarkus Flyway panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-flyway} (+ an in-memory H2 datasource) on its classpath: the migration history read from the
 * application's active {@code io.quarkus.flyway.runtime.FlywayContainer} beans by {@code QuarkusFlywayProvider}
 * is shaped by the shared engine {@code FlywayService}, and the panel is surfaced on
 * {@code GET /bootui/api/flyway/migrations} — all in-process, no Docker.
 *
 * <p>This is the flyway-<em>present</em> half of the Flyway coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the flyway-<em>absent</em> path (the capability gate
 * keeps the {@code flyway} panel unavailable, with no {@code FlywayProvider} bean). Nothing here triggers a
 * network call or a migrate/clean on render — the bundled {@code V1} migration is applied at startup by
 * {@code quarkus.flyway.migrate-at-start}, not by the panel.</p>
 */
@QuarkusTest
class BootUiQuarkusFlywayCaptureTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void flywayPanelReportsTheAppliedMigration() {
        Response report = probe().get("/bootui/api/flyway/migrations");
        assertThat(report.status())
                .as("GET /bootui/api/flyway/migrations status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/flyway/migrations content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("flywayPresent").asBoolean(false))
                .as("with quarkus-flyway present the report is available")
                .isTrue();
        assertThat(root.path("total").asInt(0))
                .as("the bundled V1 migration is counted")
                .isGreaterThanOrEqualTo(1);

        JsonNode database = firstDatabase(root);
        assertThat(database)
                .as("at least one Flyway-managed database is listed")
                .isNotNull();
        assertThat(database.path("applied").asInt(0))
                .as("the V1 migration is applied at startup")
                .isGreaterThanOrEqualTo(1);
        assertThat(database.path("currentVersion").asText(null))
                .as("the current schema version reflects the applied migration")
                .isEqualTo("1");

        JsonNode migration = firstMigration(database);
        assertThat(migration).as("the V1 migration is listed").isNotNull();
        assertThat(migration.path("version").asText(null)).isEqualTo("1");
    }

    @Test
    void flywayPanelIsReportedAvailableInTheManifest() {
        JsonNode panels = probe().get("/bootui/api/panels").json().path("panels");
        JsonNode flyway = null;
        for (JsonNode panel : panels) {
            if ("flyway".equals(panel.path("id").asText(null))) {
                flyway = panel;
                break;
            }
        }
        assertThat(flyway).as("the flyway panel is present in the manifest").isNotNull();
        assertThat(flyway.path("available").asBoolean(false))
                .as("the flyway panel is available when quarkus-flyway is present")
                .isTrue();
    }

    @Test
    void migrateRequiresExplicitConfirmationWithoutMutatingTheDatabase() {
        Response response = probe().request("POST", "/bootui/api/flyway/migrate", JSON_HEADERS, "{}");

        assertThat(response.status()).as("POST /migrate without confirm status").isEqualTo(400);
        assertThat(response.isJson())
                .as("POST /migrate without confirm content-type")
                .isTrue();
        assertThat(response.json().path("status").asText()).isEqualTo("blocked");
        assertThat(response.json().path("message").asText())
                .isEqualTo("Action requires confirm=true because it mutates the application database.");
    }

    private static JsonNode firstDatabase(JsonNode root) {
        JsonNode databases = root.path("databases");
        return databases.isArray() && !databases.isEmpty() ? databases.get(0) : null;
    }

    private static JsonNode firstMigration(JsonNode database) {
        JsonNode migrations = database.path("migrations");
        return migrations.isArray() && !migrations.isEmpty() ? migrations.get(0) : null;
    }
}
