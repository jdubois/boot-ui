package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-wiring guard for the REST API Advisor after its extraction into {@code bootui-engine}.
 *
 * <p>The shared conformance suite only exercises {@code GET /bootui/api/rest-api}, which returns the
 * cached {@code NOT_SCANNED} report with {@code controllersAnalyzed == 0}; a silently mis-wired
 * {@code BasePackageProvider} (empty base packages) would therefore still pass it. This test boots the
 * sample app and drives the autoconfigured {@link RestApiScanner} bean through a real
 * {@code POST}-equivalent {@code scan()}, asserting it resolves the sample app's base package via the
 * {@code SpringBasePackageProvider -> AutoConfigurationPackages} seam and actually analyses its
 * controllers.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-rest-api-wiring-overrides.properties"
        })
class RestApiScanWiringTest {

    @Autowired
    RestApiScanner restApiScanner;

    @Test
    void scanResolvesSampleBasePackageAndAnalysesControllers() {
        RestApiReport report = restApiScanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).contains("io.github.jdubois.bootui.sample");
        assertThat(report.controllersAnalyzed()).isPositive();
        assertThat(report.rulesEvaluated()).isPositive();
    }
}
