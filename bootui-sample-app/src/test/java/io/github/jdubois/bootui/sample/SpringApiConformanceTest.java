package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
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
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-conformance-overrides.properties"
        })
class SpringApiConformanceTest extends AbstractBootUiApiConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
