package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Spring WebFlux (reactive) adapter, by booting the reactive sample app in the Docker-free {@code dev}
 * profile (in-memory H2 backing Flyway/Liquibase, simple cache) on a random Netty port.
 *
 * <p>Proves the reactive adapter serves the exact same {@code /bootui/api/**} contract the servlet
 * adapter does (same panel ids/titles/order, same JSON shapes), just with
 * {@code platform: "spring-boot-reactive"} and the adapter-specific unavailable panels reported
 * honestly by the manifest. The raw Spring Security panel ({@code spring-security}) is live via
 * {@link io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSpringSecurityController} and
 * the Security advisor ({@code security}) is live via the reactive advisor ruleset; both report
 * {@code available: true} because the sample app registers an application
 * {@code SecurityWebFilterChain}.</p>
 *
 * <p>Panel-access conformance properties: {@code bootui.panels.copilot.enabled=false} enables
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody}; {@code
 * bootui.panels.heap-dump.read-only=true} enables
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}. Both are
 * safe to set here because safe-GET coverage skips disabled panels and never invokes heap-dump
 * actions.</p>
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/api-conformance/application-bootui.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            "bootui.conformance.api-token=conformance-raw-secret-value"
        })
class WebFluxApiConformanceTest extends AbstractBootUiApiConformanceTest {

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
        return Set.of("SampleReactiveErrorHandler", "SampleErrorController");
    }

    /**
     * The sample's security chain rejects unmapped paths before Actuator's exchange filter records them, so
     * the route probe uses a permitted, templated endpoint whose path value is the probe marker.
     */
    @Override
    protected String routeProbePath() {
        return applicationPath() + "/api/greetings/conformance-route-probe-4711";
    }

    @Override
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json";
    }

    @Override
    protected Runtime runtime() {
        return Runtime.SPRING_WEBFLUX;
    }

    @Override
    protected String exceptionProbePath() {
        return applicationPath() + "/api/sample/boom";
    }
}
