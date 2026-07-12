package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-conformance-overrides.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF"
        })
class WebFluxMcpConformanceTest extends AbstractMcpConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected boolean enableMcp() {
        BootUiHttpProbe.Response response = new BootUiHttpProbe(baseUrl())
                .request(
                        "POST",
                        "/bootui/api/mcp-server/toggle",
                        Map.of("Content-Type", "application/json"),
                        "{\"enabled\":true}");
        return response.status() == 200 && response.json().path("enabled").asBoolean(false);
    }

    @Override
    protected void disableMcp() {
        new BootUiHttpProbe(baseUrl())
                .request(
                        "POST",
                        "/bootui/api/mcp-server/toggle",
                        Map.of("Content-Type", "application/json"),
                        "{\"enabled\":false}");
    }
}
