package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Hibernate (ORM mapping) advisor on an application that <strong>does</strong>
 * have {@code quarkus-hibernate-orm} (this module adds it, on an in-memory H2 database). It is the ORM-present
 * counterpart to {@code BootUiQuarkusHibernateResourceWithoutOrmTest} in the base IT module.
 *
 * <p>It pins the Quarkus-specific end-to-end pipeline: that the {@code HIBERNATE_ORM} capability lights up the
 * panel, that {@code POST /scan} reads the application's mapped entities from the {@code EntityManagerFactory}
 * metamodel (via the capability-gated {@code QuarkusEntityDiscovery}) and runs the shared engine rule registry,
 * that a known annotation-driven advisory fires against the deliberately-imperfect {@link org.acme.hibdemo.Product}
 * entity, and — crucially — that the Spring-only Open-Session-in-View rule {@code HIB-CONFIG-001} stays inert,
 * proving {@code QuarkusHibernatePropertyLookup}'s OSIV neutralization works against a live SmallRye Config. It
 * also proves three more {@code QuarkusHibernatePropertyLookup} aliases end to end (batch fetching, statistics,
 * JDBC time zone — see {@code application.properties}): {@code HIB-FETCH-002}/{@code HIB-CONFIG-007}/
 * {@code HIB-CONFIG-013} would each false-positive on every Quarkus scan without them.</p>
 */
@QuarkusTest
class BootUiQuarkusHibernateAdvisorTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void hibernatePanelIsAvailableWhenHibernateOrmIsPresent() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        boolean hibernateAvailable = false;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("hibernate".equals(panel.path("id").asText(null))) {
                hibernateAvailable = panel.path("available").asBoolean(false);
            }
        }
        assertThat(hibernateAvailable)
                .as("the Hibernate panel is lit up when quarkus-hibernate-orm is on the classpath")
                .isTrue();
    }

    @Test
    void scanReadsTheEntityMetamodelRunsTheRulesAndNeutralizesOpenInView() {
        // A GET before any scan returns the local-only "not scanned" report.
        Response initial = probe().get("/bootui/api/hibernate");
        assertThat(initial.status()).as("GET /bootui/api/hibernate status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/hibernate content-type (%s)", initial.contentType())
                .isTrue();
        assertThat(initial.json().path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initial.json().path("scan").path("status").asText())
                .as("a GET before POST /scan reports NOT_SCANNED")
                .isEqualTo("NOT_SCANNED");

        // POST /scan reads the mapped entities from the metamodel and runs the shared rule registry.
        Response scan = probe().post("/bootui/api/hibernate/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/hibernate/scan status").isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("after POST /scan with mapped entities the report must be SCANNED")
                .isEqualTo("SCANNED");
        assertThat(scanned.path("entitiesAnalyzed").asInt())
                .as("Category, Product and Tag are all read from the metamodel")
                .isGreaterThanOrEqualTo(3);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared curated rule registry must have run")
                .isGreaterThan(0);

        List<String> violationIds = ruleIds(scanned.path("results"));
        assertThat(violationIds)
                .as("the eager @ManyToOne on Product triggers the eager-fetch advisory")
                .contains("HIB-FETCH-001");
        assertThat(violationIds)
                .as("Open-Session-in-View (HIB-CONFIG-001) must stay inert on Quarkus — there is no OSIV, so the"
                        + " property lookup reports it disabled and the rule passes")
                .doesNotContain("HIB-CONFIG-001");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.fetch.batch-size=16 (application.properties) must be read back as"
                        + " hibernate.default_batch_fetch_size, so the Product.tags lazy @ManyToMany collection is"
                        + " covered and HIB-FETCH-002 does not false-positive")
                .doesNotContain("HIB-FETCH-002");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.statistics=true must be read back as hibernate.generate_statistics, so"
                        + " HIB-CONFIG-007 does not false-positive")
                .doesNotContain("HIB-CONFIG-007");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.jdbc.timezone=UTC must be read back as hibernate.jdbc.time_zone, so"
                        + " HIB-CONFIG-013 does not false-positive")
                .doesNotContain("HIB-CONFIG-013");

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/hibernate");
        assertThat(cached.json().path("scan").path("status").asText())
                .as("the last report is cached across requests")
                .isEqualTo("SCANNED");
    }

    private static List<String> ruleIds(JsonNode resultsNode) {
        List<String> ids = new ArrayList<>();
        if (resultsNode != null && resultsNode.isArray()) {
            resultsNode.forEach(node -> ids.add(node.path("id").asText()));
        }
        return ids;
    }
}
