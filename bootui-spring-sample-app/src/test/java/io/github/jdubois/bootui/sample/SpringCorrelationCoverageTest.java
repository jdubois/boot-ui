package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.AbstractCorrelationCoverageTest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario against Spring MVC, in the Docker-free
 * {@code dev} profile. The routes cover SQL, an HTTP Basic secured request with SQL, a cached read, and a
 * request-thread exception. Buffers are raised so that no scenario request or child is evicted. SQL nests under its
 * request in every phase through BootUI's request id, which the scenario enforces.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:bootui_correlation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/correlation-coverage/application-bootui.properties",
            "bootui.http-exchanges.max-exchanges=1000",
            "bootui.activity.max-entries=2000"
        })
class SpringCorrelationCoverageTest extends AbstractCorrelationCoverageTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected String runtimeLabel() {
        return "spring-mvc";
    }

    @Override
    protected List<Traffic> traffic() {
        return List.of(
                Traffic.anonymous("/api/sample/product-search?term=console", 8),
                Traffic.basicAuth("/api/secure/products", "admin", "admin", 4),
                Traffic.anonymous("/api/sample/products", 4),
                Traffic.anonymous("/api/sample/boom", 4));
    }

    /** Since M1-3 and M1-5, request-thread SQL, security events, and cache accesses carry their request id, in every phase. */
    @Override
    protected Map<String, Double> minimumNestedShares(Phase phase) {
        return Map.of("SQL", 1.0, "SECURITY", 1.0, "CACHE", 1.0);
    }

    @Override
    protected Pattern requestThreadPattern() {
        // The sample enables virtual threads, so Tomcat serves requests on tomcat-handler-N; http-nio-…-exec-N
        // covers the platform-thread executor.
        return Pattern.compile("tomcat-handler-\\d+|http-nio-.+-exec-\\d+");
    }
}
