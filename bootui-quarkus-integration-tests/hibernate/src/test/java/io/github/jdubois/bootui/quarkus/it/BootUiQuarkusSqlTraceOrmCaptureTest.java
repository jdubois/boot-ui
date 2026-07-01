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
 * Real-boot proof that Hibernate ORM SQL is captured into the Quarkus SQL Trace panel.
 *
 * <p>The Quarkus adapter's JDBC capture ({@code BootUiSqlTraceProducer}) only wraps the CDI {@code DataSource}
 * bean, which Hibernate bypasses — it resolves its pool from Agroal's own registry. The
 * {@code BootUiHibernateStatementInspector} (a {@code @PersistenceUnitExtension} Hibernate
 * {@code StatementInspector}, capability-gated on {@code HIBERNATE_ORM}) closes that gap. This test issues a
 * JPQL query through the {@code EntityManager} via {@code /demo/products}, then asserts the SQL Trace panel is
 * available and shows the captured {@code select ... from Product} statement — exercising the inspector path
 * end to end on a real boot with {@code quarkus-hibernate-orm} on the classpath.</p>
 */
@QuarkusTest
class BootUiQuarkusSqlTraceOrmCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void hibernateOrmSqlIsCapturedIntoTheSqlTracePanel() {
        // Before any ORM SQL runs and before anything injects a DataSource, the panel has no wrapped/feeding
        // source, so it renders unavailable.
        Response initial = probe().get("/bootui/api/sql-trace");
        assertThat(initial.status()).as("GET /bootui/api/sql-trace status").isEqualTo(200);
        assertThat(initial.json().path("available").asBoolean())
                .as("no DataSource is injected in this ORM-only app, so the panel starts unavailable")
                .isFalse();

        // Issue Hibernate ORM SQL: a JPQL query through the EntityManager, which bypasses the wrapped
        // DataSource and is therefore only visible to the StatementInspector.
        Response demo = probe().get("/demo/products");
        assertThat(demo.status()).as("GET /demo/products status").isEqualTo(200);
        assertThat(demo.body().trim()).as("the demo query returns a count").isEqualTo("0");

        // The inspector registered the datasource on first inspect, so the panel is now available and the
        // captured statement is present.
        Response trace = probe().get("/bootui/api/sql-trace");
        assertThat(trace.status())
                .as("GET /bootui/api/sql-trace status after ORM SQL")
                .isEqualTo(200);
        JsonNode report = trace.json();
        assertThat(report.path("available").asBoolean())
                .as("registering the ORM datasource on first inspect makes the panel available")
                .isTrue();
        assertThat(report.path("dataSources").toString())
                .as("the ORM datasource name is surfaced")
                .contains("<default>");

        boolean capturedProductSelect = false;
        for (JsonNode entry : report.path("entries")) {
            String sql = entry.path("sql").asText("").toLowerCase();
            if (sql.contains("select") && sql.contains("product")) {
                capturedProductSelect = true;
                assertThat(entry.path("category").asText())
                        .as("the captured statement is classified as a SELECT")
                        .isEqualTo("SELECT");
            }
        }
        assertThat(capturedProductSelect)
                .as("the Hibernate-issued 'select ... from Product' statement was captured by the inspector")
                .isTrue();
        assertThat(report.path("totalCaptured").asLong())
                .as("at least one statement was captured")
                .isGreaterThan(0);
    }
}
