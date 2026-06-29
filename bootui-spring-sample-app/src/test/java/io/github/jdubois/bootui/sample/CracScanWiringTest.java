package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.crac.CracReadinessScanner;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner.CracScanResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-wiring guard for the CRaC (Coordinated Restore at Checkpoint) readiness Advisor after its
 * extraction into {@code bootui-engine}.
 *
 * <p>The shared conformance suite only exercises {@code GET /bootui/api/crac}, which returns the
 * cached {@code NOT_SCANNED} report with {@code classesAnalyzed == 0}; a silently mis-wired
 * {@code BasePackageProvider} (empty base packages) would therefore still pass it. This test boots the
 * sample app and drives the autoconfigured {@link CracReadinessScanner} bean through a real
 * {@code scan()}, asserting it resolves the sample app's base package via the
 * {@code SpringBasePackageProvider -> AutoConfigurationPackages} seam and actually analyses its classes.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-crac-wiring-overrides.properties"
        })
class CracScanWiringTest {

    @Autowired
    CracReadinessScanner cracReadinessScanner;

    @Test
    void scanResolvesSampleBasePackageAndAnalysesClasses() {
        CracScanResult result = cracReadinessScanner.scan();

        assertThat(result.status()).isEqualTo("SCANNED");
        assertThat(result.basePackages()).contains("io.github.jdubois.bootui.sample");
        assertThat(result.classesAnalyzed()).isPositive();
        assertThat(result.checksRun()).isPositive();
    }
}
