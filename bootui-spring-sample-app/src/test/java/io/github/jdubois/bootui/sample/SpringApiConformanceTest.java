package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Spring Boot adapter, by booting the sample app in the Docker-free {@code dev} profile (in-memory H2
 * backing JPA / Flyway / Liquibase, simple cache, Actuator present) on a random port.
 *
 * <p>This is the behavior safety net for the Quarkus refactor: it must stay green at every step of the
 * Phase 0 in-place refactor, and the Quarkus sample app will later run the very same contract so both
 * platforms keep serving one stable {@code /bootui/api/**} shape to the shared Vue UI.
 *
 * <p>Panel-access conformance properties: {@code bootui.panels.copilot.enabled=false} enables
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody}; {@code
 * bootui.panels.heap-dump.read-only=true} enables
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}. Both are
 * safe to set here because safe-GET coverage skips disabled panels and never invokes heap-dump
 * actions.</p>
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/api-conformance/application-bootui.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            "bootui.conformance.api-token=conformance-raw-secret-value"
        })
class SpringApiConformanceTest extends AbstractBootUiApiConformanceTest {

    @LocalServerPort
    int port;

    @Autowired
    TelemetryStore telemetryStore;

    @Override
    protected TelemetryStore telemetryStore() {
        return telemetryStore;
    }

    @Override
    protected boolean expectsResolvedSourcePaths() {
        return true;
    }

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected Set<String> expectedErrorContractComponents() {
        return Set.of("SampleGlobalErrorHandler", "SampleErrorController");
    }

    @Override
    protected String exceptionProbePath() {
        return applicationPath() + "/api/sample/boom";
    }
}
