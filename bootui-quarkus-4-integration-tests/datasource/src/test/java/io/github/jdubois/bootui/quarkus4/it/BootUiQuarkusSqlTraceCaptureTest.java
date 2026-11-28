package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.conformance.SqlTraceInsightsContract;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Inject
    HttpExchangeBuffer exchanges;

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

        // Call-site capture defaults on (bootui.sql-trace.capture-call-site). This query is issued directly
        // from this test's own @BeforeEach, which lives under BootUI's own io.github.jdubois.bootui.quarkus4 module
        // package - deny-listed as "not application code" by the same StackFramePrefixes rule that keeps BootUI's
        // own instrumentation out of the call site, exactly like a real host application's test/framework code
        // would be. The field must still be present on the wire (never omitted or throw); the Hibernate/
        // StatementInspector feeder test proves a real, non-null callSite when the query instead originates
        // from genuine application code (see BootUiQuarkusSqlTraceOrmCaptureTest).
        assertThat(entry.has("callSite"))
                .as("the callSite field is always present on the wire, even when null")
                .isTrue();
    }

    @Test
    void ranksTheRetainedWindowAndKeepsUncorrelatedWorkInAnExplicitBucket() {
        Response response = probe().get("/bootui/api/sql-trace/insights");
        assertThat(response.status())
                .as("GET /bootui/api/sql-trace/insights status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").asBoolean(false))
                .as("rankings are available once a datasource is wrapped")
                .isTrue();
        assertThat(root.path("window").path("retainedStatements").asLong(0))
                .as("the report states the bounded window it ranked")
                .isGreaterThan(0);

        JsonNode statements = root.path("statements");
        assertThat(statements.size()).as("the executed statement is ranked").isGreaterThan(0);
        assertThat(statements.toString())
                .as("a ranked statement never carries a bound value")
                .doesNotContain("secret-value");
        assertThat(statements.get(0).path("topFor").isArray())
                .as("a ranked statement declares which criteria earned it its place")
                .isTrue();

        JsonNode attribution = root.path("attribution");
        assertThat(attribution.path("supportedCorrelations").toString())
                .as("Quarkus has no thread-per-request anchor and must not claim one")
                .doesNotContain("SERVING_THREAD");
        // This query was issued from the test's own @BeforeEach, not from an inbound HTTP request, so it
        // must land in the visible unattributed bucket rather than being guessed onto a route.
        assertThat(attribution.path("unattributed").path("executions").asLong(0))
                .as("work with no inbound request stays explicitly unattributed")
                .isGreaterThan(0);
        assertThat(attribution.path("unattributed").path("reason").asText(""))
                .as("the unattributed bucket explains itself")
                .isNotBlank();
    }

    @Test
    void sqlTraceInsightsAreBoundedAndDiscloseTheirCorrelationTiers() throws InterruptedException {
        exchanges.offloadRetainedData();
        CountDownLatch requestCaptured = new CountDownLatch(1);
        Runnable unsubscribe = exchanges.subscribe(() -> {
            if (exchanges.snapshot().stream()
                    .anyMatch(exchange -> exchange.uri() != null
                            && "/it/sql-trace/4711".equals(exchange.uri().getPath()))) {
                requestCaptured.countDown();
            }
        });
        try {
            Response request = probe().get("/it/sql-trace/4711?access_token=synthetic-route-secret");
            assertThat(request.status()).isEqualTo(200);
            assertThat(request.body()).isEqualTo("4711");
            assertThat(requestCaptured.await(10, TimeUnit.SECONDS))
                    .as("response completion must reach the exchange buffer before reading attribution")
                    .isTrue();

            JsonNode root = SqlTraceInsightsContract.read(probe(), "/bootui/api");
            assertThat(root.path("available").asBoolean())
                    .as("the native Agroal fixture must execute, never skip, the shared SQL insights contract")
                    .isTrue();
            assertThat(root.path("window").path("retainedStatements").asInt()).isPositive();
            assertThat(root.path("statements")).isNotEmpty();
            JsonNode routes = root.path("attribution").path("routes");
            assertThat(routes)
                    .as("the native HTTP request must attribute its JDBC work (%s)", root.path("attribution"))
                    .hasSize(1);
            JsonNode route = routes.get(0);
            assertThat(route.path("method").asText()).isEqualTo("GET");
            assertThat(route.path("routeSource").asText()).isEqualTo("ROUTE_TEMPLATE");
            assertThat(route.path("route").asText()).isEqualTo("/it/sql-trace/{id}");
            assertThat(route.path("executions").asLong()).isPositive();
            assertThat(route.path("timeWindowCorrelated").asLong()).isPositive();
            assertThat(route.path("topStatements")).isNotEmpty();
            assertThat(route.path("topStatements").toString()).containsIgnoringCase("bootui_http_marker");
            assertThat(route.path("id").asText()).doesNotContain("4711", "synthetic-route-secret", "access_token");
            assertThat(routes.toString()).doesNotContain("synthetic-route-secret", "access_token");
            SqlTraceInsightsContract.sqlTraceInsightsAreBoundedAndDiscloseTheirCorrelationTiers(root);
        } finally {
            unsubscribe.run();
            // A completed request window must not claim a later test's unrelated test-thread query.
            exchanges.offloadRetainedData();
        }
    }

    @Test
    void sqlTraceStreamOpens() throws Exception {
        // The SSE change-notification stream backs the shared Vue panel's auto-refresh toggle; it must open
        // (200 + text/event-stream) whenever a recorder is wrapped, mirroring the Spring adapter. The push
        // payload is pinned deterministically by the engine SqlTraceRecorder unit tests.
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.toExternalForm().replaceAll("/$", "") + "/bootui/api/sql-trace/stream"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();
        assertThat(response.statusCode())
                .as("GET /bootui/api/sql-trace/stream status")
                .isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .as("GET /bootui/api/sql-trace/stream content-type")
                .contains("text/event-stream");
    }
}
