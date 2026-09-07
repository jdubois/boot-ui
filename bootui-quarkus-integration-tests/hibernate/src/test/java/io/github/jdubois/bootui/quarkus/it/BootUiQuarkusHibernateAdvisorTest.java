package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Hibernate (ORM mapping) advisor on an application that <strong>does</strong>
 * have {@code quarkus-hibernate-orm} (this module adds it, on an in-memory H2 database). It is the ORM-present
 * counterpart to {@code BootUiQuarkusHibernateResourceWithoutOrmTest} in the base IT module.
 *
 * <p>It pins the Quarkus-specific end-to-end pipeline: that the {@code HIBERNATE_ORM} capability lights up the
 * panel, that {@code POST /scan} reads the application's mapped entities from the {@code EntityManagerFactory}
 * metamodel (via the capability-gated {@code QuarkusHibernateAdvisorObservationSource}) and runs the shared engine rule registry,
 * that a known annotation-driven advisory fires against the deliberately-imperfect {@link org.acme.hibdemo.Product}
 * entity, and — crucially — that the Spring-only Open-Session-in-View rule {@code HIB-CONFIG-001} stays inert,
 * proving OSIV is classified not applicable. Native factory defaults, rather than property aliases,
 * provide batch/cache/statistics evidence; unavailable scalar observations remain incomplete instead
 * of producing false-positive findings.</p>
 */
@QuarkusTest
class BootUiQuarkusHibernateAdvisorTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Inject
    EntityManagerFactory entityManagerFactory;

    @Inject
    io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource observationSource;

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
                .as("missing required evidence must not be presented as a complete clean evaluation")
                .isEqualTo("PARTIAL");
        assertThat(scanned.path("scan").path("message").asText()).contains("required evidence unavailable");
        assertThat(scanned.path("entitiesAnalyzed").asInt())
                .as("Category, Product and Tag are all read from the metamodel")
                .isGreaterThanOrEqualTo(3);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared curated rule registry must have run")
                .isEqualTo(70);

        List<String> violationIds = ruleIds(scanned.path("results"));
        assertThat(violationIds)
                .doesNotHaveDuplicates()
                .doesNotContain("HIB-FETCH-004", "HIB-MAP-012", "HIB-ENTITY-003", "HIB-ENTITY-004", "HIB-MAP-019");
        for (JsonNode result : scanned.path("results")) {
            assertThat(result.path("status").asText()).isEqualTo("VIOLATION");
            for (JsonNode sample : result.path("sampleViolations"))
                assertThat(sample.asText()).startsWith("[");
        }
        assertThat(violationIds)
                .as("the eager @ManyToOne on Product triggers the eager-fetch advisory")
                .contains("HIB-FETCH-001");
        assertThat(violationIds)
                .as("Open-Session-in-View (HIB-CONFIG-001) must stay inert on Quarkus — there is no OSIV, so the"
                        + " observation reports it not applicable")
                .doesNotContain("HIB-CONFIG-001");
        assertThat(violationIds)
                .as("the effective factory fetch batch size is 16, so the Product.tags lazy @ManyToMany collection is"
                        + " covered and HIB-FETCH-002 does not false-positive")
                .doesNotContain("HIB-FETCH-002");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.statistics=true must be read back as hibernate.generate_statistics, so"
                        + " HIB-CONFIG-007 does not false-positive")
                .doesNotContain("HIB-CONFIG-007");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.log.queries-slower-than-ms=25 must be read back as Hibernate's"
                        + " slow-query threshold, so HIB-CONFIG-006 does not false-positive")
                .doesNotContain("HIB-CONFIG-006");
        assertThat(violationIds)
                .as("Quarkus-managed second-level caching must satisfy HIB-CONFIG-010 without requiring an"
                        + " unsupported explicit Hibernate region-factory property")
                .doesNotContain("HIB-CONFIG-010");
        assertThat(violationIds)
                .as("quarkus.hibernate-orm.jdbc.timezone=UTC must be read back as hibernate.jdbc.time_zone, so"
                        + " HIB-CONFIG-013 does not false-positive")
                .doesNotContain("HIB-CONFIG-013");

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/hibernate");
        assertThat(cached.json().path("scan").path("status").asText())
                .as("the last report is cached across requests")
                .isEqualTo(scanned.path("scan").path("status").asText());
    }

    @Test
    void observationUsesNativeQuarkusFactoryDefaultsAndVerifiedEnhancement() {
        var observed = observationSource.observe();
        var options =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        assertThat(observed.units()).singleElement().satisfies(unit -> {
            assertThat(unit.settings().jdbcBatchSize()).isEqualTo(options.getJdbcBatchSize());
            assertThat(unit.settings().defaultBatchFetchSize()).isEqualTo(options.getDefaultBatchFetchSize());
            assertThat(unit.settings().queryCache()).isEqualTo(options.isQueryCacheEnabled());
            assertThat(unit.settings().secondLevelCache()).isEqualTo(options.isSecondLevelCacheEnabled());
            assertThat(unit.enhancementVerified()).isTrue();
            assertThat(unit.repositories()).isEmpty();
        });
        assertThat(observed.diagnostics()).isEmpty();
    }

    @Test
    void hibernateStatisticsPanelIsAvailableWhenOrmStatisticsAreEnabled() {
        // quarkus.hibernate-orm.statistics=true (application.properties) enables Statistics collection, so
        // the additive Session Monitoring panel must report available rather than the "enable statistics"
        // unavailable state.
        Response statistics = probe().get("/bootui/api/hibernate-statistics");
        assertThat(statistics.status())
                .as("GET /bootui/api/hibernate-statistics status")
                .isEqualTo(200);
        JsonNode body = statistics.json();
        assertThat(body.path("available").asBoolean(false))
                .as("statistics must be available since quarkus.hibernate-orm.statistics=true")
                .isTrue();
        assertThat(body.path("statistics").isMissingNode())
                .as("the statistics payload must be present when available")
                .isFalse();
        assertThat(body.path("statistics").path("sessionOpenCount").isNumber())
                .as("sessionOpenCount must be a numeric counter")
                .isTrue();
    }

    @Test
    void hibernateStatisticsCanBeEnabledForTheCurrentRuntime() {
        Statistics liveStatistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        liveStatistics.setStatisticsEnabled(false);
        try {
            Response disabled = probe().get("/bootui/api/hibernate-statistics");
            assertThat(disabled.status()).isEqualTo(200);
            assertThat(disabled.json().path("available").asBoolean(true)).isFalse();
            assertThat(disabled.json().path("enableAvailable").asBoolean(false)).isTrue();

            Response enabled = probe().post("/bootui/api/hibernate-statistics/enable", JSON_HEADERS);
            assertThat(enabled.status()).isEqualTo(200);
            assertThat(enabled.json().path("available").asBoolean(false)).isTrue();
            assertThat(liveStatistics.isStatisticsEnabled()).isTrue();
        } finally {
            liveStatistics.setStatisticsEnabled(true);
        }
    }

    private static List<String> ruleIds(JsonNode resultsNode) {
        List<String> ids = new ArrayList<>();
        if (resultsNode != null && resultsNode.isArray()) {
            resultsNode.forEach(node -> ids.add(node.path("id").asText()));
        }
        return ids;
    }
}
