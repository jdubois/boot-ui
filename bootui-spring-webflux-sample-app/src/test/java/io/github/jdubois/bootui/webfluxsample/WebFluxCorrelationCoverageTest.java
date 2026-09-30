package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractCorrelationCoverageTest;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario against Spring WebFlux, in the {@code dev}
 * profile. The routes cover blocking JDBC offloaded to a Reactor scheduler, a cached read, and a failing request.
 * The sample secures no application route, so the scenario has no security traffic on this stack.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/correlation-coverage/application-bootui.properties",
            "bootui.http-exchanges.max-exchanges=1000",
            "bootui.activity.max-entries=2000"
        })
class WebFluxCorrelationCoverageTest extends AbstractCorrelationCoverageTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected String runtimeLabel() {
        return "spring-webflux";
    }

    @Override
    protected List<Traffic> traffic() {
        return List.of(
                Traffic.anonymous("/api/notes", 8),
                Traffic.anonymous("/api/greetings/bootui", 4),
                Traffic.anonymous("/api/sample/boom", 4));
    }

    @Override
    protected Pattern requestThreadPattern() {
        // Netty event loops, plus the Reactor schedulers the sample offloads request work to.
        return Pattern.compile("reactor-http-.+|(loom)?[bB]oundedElastic-.+|parallel-.+");
    }
}
