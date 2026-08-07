package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Proves the shared REST API Advisor is wired through the reactive adapter with the host application's
 * base package, rather than merely serving the cached report contract.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-rest-api-wiring-overrides.properties"
        })
class WebFluxRestApiScanWiringTest {

    @Autowired
    RestApiScanner restApiScanner;

    @Test
    void scanResolvesWebFluxSampleBasePackageAndAnalysesControllers() {
        RestApiReport report = restApiScanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).contains("io.github.jdubois.bootui.webfluxsample");
        assertThat(report.controllersAnalyzed()).isPositive();
        assertThat(report.rulesEvaluated()).isPositive();
    }
}
