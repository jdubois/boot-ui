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
 * Pins the Liquibase panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-liquibase} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the liquibase-<em>absent</em> half of the Liquibase coverage (the liquibase-present light-up path
 * lives in the dedicated {@code bootui-quarkus-liquibase-integration-tests} module). It proves the R2
 * capability gate fails closed: the {@code io.quarkus.liquibase.*}-importing {@code BootUiLiquibaseProducer} is
 * excluded by the deployment build step when {@code Capability.LIQUIBASE} is absent, so no
 * {@code LiquibaseProvider} bean exists and the {@code liquibase} panel is reported <em>unavailable</em> in the
 * manifest with an honest capability hint — while the liquibase-API-free engine {@code LiquibaseService} is
 * still wired, so {@code GET /bootui/api/liquibase/changesets} answers with valid JSON reporting
 * {@code liquibasePresent:false} (no {@code NoClassDefFoundError} from the absent backend).</p>
 */
@QuarkusTest
class BootUiQuarkusLiquibaseResourceWithoutLiquibaseTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void liquibasePanelIsUnavailableWithACapabilityHintWithoutQuarkusLiquibase() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode liquibase = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("liquibase".equals(panel.path("id").asText(null))) {
                liquibase = panel;
            }
        }
        assertThat(liquibase)
                .as("the Liquibase panel is present in the manifest")
                .isNotNull();
        assertThat(liquibase.path("available").asBoolean(true))
                .as("the Liquibase panel is unavailable when quarkus-liquibase is absent")
                .isFalse();
        assertThat(liquibase.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-liquibase");
    }

    @Test
    void liquibaseReportRendersUnavailableWithoutQuarkusLiquibase() {
        Response report = probe().get("/bootui/api/liquibase/changesets");
        assertThat(report.status())
                .as("GET /bootui/api/liquibase/changesets status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/liquibase/changesets content-type (%s)", report.contentType())
                .isTrue();
        assertThat(report.json().path("liquibasePresent").asBoolean(true))
                .as("the report is unavailable when no LiquibaseProvider bean is wired")
                .isFalse();
    }
}
