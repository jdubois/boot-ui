package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
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
 * {@code SpringHibernateDiscovery -> JpaMetamodelReader} seam and actually analyses the sample app's
 * mapped entities.
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

    @Test
    void scanReadsMetamodelAndAnalysesEntities() {
        HibernateReport report = hibernateScanner.scan();

        // A clean metamodel walk reports SCANNED; a benign discovery hiccup reports PARTIAL. Either proves
        // the scan ran; the load-bearing assertion is that real mapped entities were actually analysed.
        assertThat(report.scan().status()).isIn("SCANNED", "PARTIAL");
        assertThat(report.entitiesAnalyzed()).isPositive();
    }
}
