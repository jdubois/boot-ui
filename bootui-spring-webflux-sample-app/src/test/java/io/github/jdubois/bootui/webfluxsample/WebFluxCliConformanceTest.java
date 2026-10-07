package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractCliConformanceTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/cli-conformance/application-bootui.properties",
            "bootui.panels.memory.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            // AbstractCliConformanceTest.CLI_TIMEOUT: a loaded runner can stretch architecture_scan past the 30 s
            // default.
            "bootui.cli.execution-timeout=60s"
        })
class WebFluxCliConformanceTest extends AbstractCliConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
