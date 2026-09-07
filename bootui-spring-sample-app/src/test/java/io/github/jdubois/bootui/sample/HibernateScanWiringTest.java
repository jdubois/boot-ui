package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-wiring guard for the Hibernate advisor after its extraction into {@code bootui-engine}.
 *
 * <p>The shared conformance suite only exercises {@code GET /bootui/api/hibernate}, which returns the
 * cached {@code NOT_SCANNED} report with {@code entitiesAnalyzed == 0}; a silently mis-wired entity
 * discovery (no {@code EntityManagerFactory} reached, empty metamodel) would therefore still pass it.
 * This test boots the sample app and drives the autoconfigured {@link HibernateScanner} bean through a
 * real {@code POST}-equivalent {@code scan()}, asserting it reads the JPA metamodel via the
 * {@code SpringHibernateAdvisorObservationSource -> JpaMetamodelReader} seam, pairs those entities
 * with native factory options and verified JPA repositories, and actually analyses mapped entities.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-hibernate-wiring-overrides.properties"
        })
class HibernateScanWiringTest {

    @Autowired
    HibernateScanner hibernateScanner;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    HibernateAdvisorObservationSource observationSource;

    @Test
    void scanReadsMetamodelAndAnalysesEntities() {
        HibernateReport report = hibernateScanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.entitiesAnalyzed()).isPositive();
        assertThat(report.rulesEvaluated()).isEqualTo(70);
        assertThat(report.results()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations()).allMatch(sample -> sample.startsWith("["));
        });
        assertThat(report.results())
                .extracting(result -> result.id())
                .doesNotHaveDuplicates()
                .doesNotContain("HIB-FETCH-004", "HIB-MAP-012", "HIB-ENTITY-003", "HIB-ENTITY-004", "HIB-MAP-019");
        var factory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        var options = factory.getSessionFactoryOptions();
        var observed = observationSource.observe();
        assertThat(observed.diagnostics()).isEmpty();
        assertThat(observed.units()).singleElement().satisfies(unit -> {
            assertThat(unit.entities()).isNotEmpty();
            assertThat(unit.repositories()).isNotEmpty();
            assertThat(unit.settings().jdbcBatchSize()).isEqualTo(options.getJdbcBatchSize());
            assertThat(unit.settings().defaultBatchFetchSize()).isEqualTo(options.getDefaultBatchFetchSize());
            assertThat(unit.settings().orderInserts()).isEqualTo(options.isOrderInsertsEnabled());
            assertThat(unit.settings().orderUpdates()).isEqualTo(options.isOrderUpdatesEnabled());
            assertThat(unit.settings().queryCache()).isEqualTo(options.isQueryCacheEnabled());
            assertThat(unit.settings().secondLevelCache()).isEqualTo(options.isSecondLevelCacheEnabled());
            assertThat(unit.settings().paginationGuard())
                    .isEqualTo(options.isFailOnPaginationOverCollectionFetchEnabled());
        });
    }
}
