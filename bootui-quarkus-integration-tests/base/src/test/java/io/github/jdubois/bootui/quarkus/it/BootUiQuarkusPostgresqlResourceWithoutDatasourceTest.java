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
 * Pins the PostgreSQL panel's behavior on a Quarkus app that has <strong>no</strong> JDBC datasource on its
 * classpath (this base integration-test module deliberately omits one).
 *
 * <p>The engine {@code PostgresInsightService} and its resource are wired <strong>unconditionally</strong>
 * (their only hard dependency is {@code javax.sql.DataSource}, which is core JDK), so the endpoint answers
 * honestly even with no datasource: {@code GET /bootui/api/postgresql} reports {@code NOT_READ} before any
 * read, and {@code POST /read} renders a stable, empty {@code DISABLED} report (not an error) when no
 * {@code DataSource} bean is found. The panel's <em>manifest availability</em>, however, is gated on a JDBC
 * datasource being present (like SQL Trace), so with none present the panel is reported unavailable with an
 * honest, PostgreSQL-specific hint. The datasource-present light-up path lives in the JDK-gated
 * {@code bootui-quarkus-hibernate-integration-tests} module (which also adds an H2 datasource).</p>
 */
@QuarkusTest
class BootUiQuarkusPostgresqlResourceWithoutDatasourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void postgresqlPanelIsUnavailableWithAHintWhenNoDatasourceIsPresent() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode postgresql = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("postgresql".equals(panel.path("id").asText(null))) {
                postgresql = panel;
            }
        }
        assertThat(postgresql)
                .as("the PostgreSQL panel is present in the manifest")
                .isNotNull();
        assertThat(postgresql.path("available").asBoolean(true))
                .as("the panel is unavailable without a PostgreSQL datasource — it reads PostgreSQL's own views")
                .isFalse();
        assertThat(postgresql.path("unavailableReason").asText(""))
                .as("the panel carries an honest, PostgreSQL-specific hint")
                .contains("PostgreSQL datasource");
    }

    @Test
    void postgresqlRendersNotReadThenDisabledWithoutADataSource() {
        // A GET before any read returns the local-only "not read" report; the service is wired even though no
        // DataSource bean exists.
        Response initial = probe().get("/bootui/api/postgresql");
        assertThat(initial.status()).as("GET /bootui/api/postgresql status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/postgresql content-type (%s)", initial.contentType())
                .isTrue();
        JsonNode initialBody = initial.json();
        assertThat(initialBody.path("localOnly").asBoolean())
                .as("the PostgreSQL report must be flagged local-only")
                .isTrue();
        assertThat(initialBody.path("status").asText())
                .as("a GET before POST /read reports NOT_READ, not DISABLED")
                .isEqualTo("NOT_READ");

        // POST /read with no DataSource bean present renders DISABLED (not an error) — the panel degrades
        // gracefully instead of failing the request or attempting a connection that cannot exist.
        Response read = probe().post("/bootui/api/postgresql/read", JSON_HEADERS);
        assertThat(read.status()).as("POST /bootui/api/postgresql/read status").isEqualTo(200);
        JsonNode readBody = read.json();
        assertThat(readBody.path("status").asText())
                .as("with no DataSource bean the read reports DISABLED")
                .isEqualTo("DISABLED");
        assertThat(readBody.path("databasesRead").asInt())
                .as("no databases are read without a DataSource")
                .isEqualTo(0);
        assertThat(readBody.path("databases").isArray())
                .as("databases must still be a (empty) JSON array, not null")
                .isTrue();
        assertThat(readBody.path("databases")).isEmpty();
    }
}
