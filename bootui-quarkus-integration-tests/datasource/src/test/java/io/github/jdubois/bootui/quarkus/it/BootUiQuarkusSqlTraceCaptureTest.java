package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus SQL Trace panel light-up end to end on an app with a JDBC datasource
 * ({@code quarkus-jdbc-h2}, backed by Agroal): the application's default datasource is wrapped by the shared
 * {@code SqlTracingProxies}, executed JDBC statements land in the engine {@code SqlTraceRecorder}, and
 * {@code GET /bootui/api/sql-trace} renders the same neutral {@code SqlTraceReport} wire as the Spring
 * adapter. Bound parameter values stay masked because {@code capture-parameters} defaults off, so the panel
 * never leaks values — all in-process, no Docker.
 */
@QuarkusTest
class BootUiQuarkusSqlTraceCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    DataSource dataSource;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @BeforeEach
    void runTracedQuery() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT ? AS bootui_marker")) {
            statement.setString(1, "secret-value");
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
            }
        }
    }

    @Test
    void capturesQueryAndMasksBoundParameters() {
        Response report = probe().get("/bootui/api/sql-trace");
        assertThat(report.status()).as("GET /bootui/api/sql-trace status").isEqualTo(200);
        assertThat(report.isJson())
                .as("content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("available").asBoolean(false))
                .as("the panel is available when a JDBC datasource is wrapped")
                .isTrue();
        assertThat(root.path("capturing").asBoolean(false))
                .as("recording is on by default")
                .isTrue();
        assertThat(root.path("captureParameters").asBoolean(true))
                .as("parameter capture is off by default")
                .isFalse();

        JsonNode entries = root.path("entries");
        assertThat(entries.isArray()).as("entries are present").isTrue();
        assertThat(entries.size()).as("at least one statement captured").isGreaterThan(0);

        JsonNode entry = entries.get(0);
        assertThat(entry.path("sql").asText(""))
                .as("the executed SQL is captured")
                .contains("SELECT");
        assertThat(entry.path("parameters").isArray()).isTrue();
        assertThat(entry.path("parameters").size())
                .as("bound parameter values stay masked while capture-parameters is off")
                .isEqualTo(0);
    }
}
