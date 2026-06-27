package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-wiring guard for the GraalVM native-image readiness Advisor after its extraction into
 * {@code bootui-engine}.
 *
 * <p>The shared conformance suite only exercises {@code GET /bootui/api/graalvm}, which returns the
 * cached {@code NOT_SCANNED} report with {@code classesAnalyzed == 0}; a silently mis-wired
 * {@code BasePackageProvider} (empty base packages) would therefore still pass it. This test boots the
 * sample app and drives the autoconfigured {@link GraalVmReadinessScanner} bean through a real
 * {@code scan(false)} (dependencies off, so no network), asserting it resolves the sample app's base
 * package via the {@code SpringBasePackageProvider -> AutoConfigurationPackages} seam and actually
 * analyses its classes.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-graalvm-wiring-overrides.properties"
        })
class GraalVmScanWiringTest {

    @Autowired
    GraalVmReadinessScanner graalVmReadinessScanner;

    @Test
    void scanResolvesSampleBasePackageAndAnalysesClasses() {
        GraalVmScanResult result = graalVmReadinessScanner.scan(false);
        GraalVmReadinessReport report = result.report();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).contains("io.github.jdubois.bootui.sample");
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.checksRun()).isPositive();
    }
}
