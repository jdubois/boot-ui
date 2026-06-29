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
 * Pins the Database Connection Pools panel's behavior on a Quarkus app that has <strong>no</strong> JDBC
 * datasource on its classpath (this integration-test module deliberately omits one, so the {@code AGROAL}
 * capability is absent).
 *
 * <p>This is the datasource-<em>absent</em> half of the coverage (the datasource-present light-up path lives in
 * the dedicated {@code bootui-quarkus-datasource-integration-tests} module). It proves the R2 capability gate
 * fails closed: the {@code io.agroal.*}-importing {@code BootUiAgroalProducer} is excluded by the deployment
 * build step when {@code Capability.AGROAL} is absent, so no {@code ConnectionPoolProvider} bean exists and the
 * panel is reported <em>unavailable</em> in the manifest with an honest hint — while the Agroal-API-free engine
 * {@code ConnectionPoolService} is still wired, so {@code GET /bootui/api/database-connection-pools/pools}
 * answers with valid JSON reporting {@code hikariPresent:false} (no {@code NoClassDefFoundError} from the absent
 * backend).</p>
 */
@QuarkusTest
class BootUiQuarkusConnectionPoolsResourceWithoutDatasourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void connectionPoolsPanelIsUnavailableWithAHintWithoutDatasource() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode pools = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("database-connection-pools".equals(panel.path("id").asText(null))) {
                pools = panel;
            }
        }
        assertThat(pools)
                .as("the Database Connection Pools panel is present in the manifest")
                .isNotNull();
        assertThat(pools.path("available").asBoolean(true))
                .as("the panel is unavailable when no JDBC datasource is present")
                .isFalse();
        assertThat(pools.path("unavailableReason").asText(null))
                .as("the unavailable reason names a JDBC datasource extension to add")
                .contains("JDBC datasource");
    }

    @Test
    void poolsReportRendersEmptyWithoutDatasource() {
        Response report = probe().get("/bootui/api/database-connection-pools/pools");
        assertThat(report.status())
                .as("GET /bootui/api/database-connection-pools/pools status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/database-connection-pools/pools content-type (%s)", report.contentType())
                .isTrue();
        JsonNode root = report.json();
        assertThat(root.path("hikariPresent").asBoolean(true))
                .as("the report is absent when no ConnectionPoolProvider bean is wired")
                .isFalse();
        assertThat(root.path("total").asInt(-1)).as("no pools are listed").isZero();
    }
}
