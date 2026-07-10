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
 * Proves the Quarkus Liquibase panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-liquibase} (over an in-memory H2 datasource) on its classpath: the change-log history read
 * from the application's {@code io.quarkus.liquibase.LiquibaseFactory} by {@code QuarkusLiquibaseProvider} is
 * shaped by the shared engine {@code LiquibaseService} and surfaced on
 * {@code GET /bootui/api/liquibase/changesets}, the panel is reported available in the manifest, and the
 * {@code POST /update} action runs idempotently — all in-process, no Docker.
 *
 * <p>This is the liquibase-<em>present</em> half of the Liquibase coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the liquibase-<em>absent</em> path (the capability
 * gate keeps the {@code liquibase} panel unavailable, with no {@code LiquibaseProvider} bean). Nothing here
 * triggers a network call on render; the migration runs at startup and the only mutating request is the
 * explicit {@code POST /update}, which is a no-op because the database is already up to date.</p>
 */
@QuarkusTest
class BootUiQuarkusLiquibaseCaptureTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void liquibasePanelReportsAppliedChangeSets() {
        Response report = probe().get("/bootui/api/liquibase/changesets");
        assertThat(report.status())
                .as("GET /bootui/api/liquibase/changesets status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/liquibase/changesets content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("liquibasePresent").asBoolean(false))
                .as("with quarkus-liquibase present the report is present")
                .isTrue();
        assertThat(root.path("total").asInt(0))
                .as("the two seeded change sets are applied")
                .isGreaterThanOrEqualTo(2);

        JsonNode databases = root.path("databases");
        assertThat(databases.isArray() && databases.size() >= 1)
                .as("at least one managed Liquibase datasource is listed")
                .isTrue();

        JsonNode database = databases.get(0);
        assertThat(database.path("applied").asInt(0))
                .as("the applied change-set count for the datasource")
                .isGreaterThanOrEqualTo(2);
        assertThat(changeSetIds(database))
                .as("both seeded change sets are reported as applied")
                .contains("create-customers", "seed-customers");
    }

    @Test
    void manifestReportsTheLiquibasePanelAvailable() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode liquibase = findPanel(panels.json(), "liquibase");
        assertThat(liquibase).as("the manifest lists the liquibase panel").isNotNull();
        assertThat(liquibase.path("available").asBoolean(false))
                .as("the liquibase panel is available with quarkus-liquibase present")
                .isTrue();
    }

    @Test
    void updateRequiresExplicitConfirmationWithoutMutatingTheDatabase() {
        Response response = probe().request("POST", "/bootui/api/liquibase/update", JSON_HEADERS, "{}");

        assertThat(response.status()).as("POST /update without confirm status").isEqualTo(400);
        assertThat(response.isJson())
                .as("POST /update without confirm content-type")
                .isTrue();
        assertThat(response.json().path("status").asText()).isEqualTo("blocked");
        assertThat(response.json().path("message").asText())
                .isEqualTo("Action requires confirm=true because it mutates the application database.");
    }

    @Test
    void updateIsANoOpWhenAlreadyUpToDate() {
        Response update = probe().request("POST", "/bootui/api/liquibase/update", JSON_HEADERS, "{\"confirm\":true}");
        assertThat(update.status()).as("POST /update status").isEqualTo(200);

        JsonNode body = update.json();
        assertThat(body.path("status").asText()).as("update result status").isEqualTo("success");
        assertThat(body.path("changeSetsApplied").asInt(-1))
                .as("the database is already up to date, so no change sets are applied")
                .isEqualTo(0);
    }

    private static java.util.List<String> changeSetIds(JsonNode database) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode changeSet : database.path("changeSets")) {
            ids.add(changeSet.path("id").asText(null));
        }
        return ids;
    }

    private static JsonNode findPanel(JsonNode manifest, String panelId) {
        for (JsonNode panel : manifest.path("panels")) {
            if (panelId.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        return null;
    }
}
