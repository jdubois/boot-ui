package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.vulnerabilities.osv-enabled=false",
            "bootui.vulnerabilities.osv-base-uri=http://127.0.0.1:1",
            "bootui.overrides-file=target/vulnerabilities-disabled-it/application-bootui.properties"
        })
class SpringVulnerabilitiesDisabledIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void disabledScannerKeepsInventoryLocalAndRefusesTheScan() {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        JsonNode inventory = probe.get("/bootui/api/vulnerabilities").json();
        assertThat(inventory.path("scan").path("status").asText()).isEqualTo("NOT_SCANNED");
        assertThat(inventory.path("scanningEnabled").asBoolean()).isFalse();
        assertThat(inventory.path("dependencies").size()).isGreaterThan(0);

        probe.get("/bootui/api/overview");
        String token = probe.cookie("XSRF-TOKEN").orElseThrow();
        Response scan = probe.request("POST", "/bootui/api/vulnerabilities/scan", Map.of("X-XSRF-TOKEN", token), "");
        assertThat(scan.status()).isEqualTo(200);
        assertThat(scan.json().path("scan").path("status").asText()).isEqualTo("DISABLED");
        assertThat(scan.json().path("scanningEnabled").asBoolean()).isFalse();
    }
}
