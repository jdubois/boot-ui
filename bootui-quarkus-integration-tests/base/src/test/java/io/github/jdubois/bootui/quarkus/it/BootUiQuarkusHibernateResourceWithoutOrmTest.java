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
 * Pins the Hibernate (ORM mapping) advisor's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-hibernate-orm} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the Hibernate-<em>absent</em> half of the advisor coverage (the ORM-present scan path lives in
 * the JDK-gated {@code bootui-quarkus-hibernate-integration-tests} module). It proves the R2 safety guarantee:
 * the engine {@code HibernateScanner} is wired unconditionally (it holds no {@code jakarta.persistence} types),
 * so {@code GET /bootui/api/hibernate} answers with valid JSON and {@code POST /scan} renders a DISABLED report,
 * while the {@code jakarta.persistence}-importing {@code BootUiHibernateProducer} is never registered — the app
 * boots clean with no {@code NoClassDefFoundError} from the absent backend. The Hibernate <em>panel</em>, unlike
 * the always-available Health/Loggers panels, is reported <em>unavailable</em> in the manifest with an honest
 * capability hint, because there is no Hibernate ORM to advise on.</p>
 */
@QuarkusTest
class BootUiQuarkusHibernateResourceWithoutOrmTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void hibernatePanelIsUnavailableWithACapabilityHintWithoutHibernateOrm() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode hibernate = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("hibernate".equals(panel.path("id").asText(null))) {
                hibernate = panel;
            }
        }
        assertThat(hibernate)
                .as("the Hibernate panel is present in the manifest")
                .isNotNull();
        assertThat(hibernate.path("available").asBoolean(true))
                .as("the Hibernate panel is unavailable when quarkus-hibernate-orm is absent")
                .isFalse();
        assertThat(hibernate.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-hibernate-orm");
    }

    @Test
    void hibernateAdvisorRendersNotScannedThenDisabledWithoutEntities() {
        // A GET before any scan returns the local-only "not scanned" report; the scanner is wired even though
        // the entity-discovery source is unsatisfied.
        Response initial = probe().get("/bootui/api/hibernate");
        assertThat(initial.status()).as("GET /bootui/api/hibernate status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/hibernate content-type (%s)", initial.contentType())
                .isTrue();
        JsonNode initialBody = initial.json();
        assertThat(initialBody.path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initialBody.path("scan").path("status").asText())
                .as("a GET before POST /scan reports NOT_SCANNED, not DISABLED")
                .isEqualTo("NOT_SCANNED");

        // POST /scan with no EntityManagerFactory present renders DISABLED (not an error) — the panel degrades
        // gracefully instead of failing the request.
        Response scan = probe().post("/bootui/api/hibernate/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/hibernate/scan status").isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("with no mapped entities the scan reports DISABLED")
                .isEqualTo("DISABLED");
        assertThat(scanned.path("entitiesAnalyzed").asInt())
                .as("no entities are analysed without Hibernate ORM")
                .isEqualTo(0);
    }
}
