package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
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
 * {@code platform: "spring-boot-reactive"} and the two panels with no reactive equivalent
 * (http-sessions, spring-security) reporting {@code available: false}.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-conformance-overrides.properties"
        })
class WebFluxApiConformanceTest extends AbstractBootUiApiConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json";
    }
}
