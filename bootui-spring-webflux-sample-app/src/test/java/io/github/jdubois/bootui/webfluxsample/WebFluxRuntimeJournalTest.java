package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The runtime journal on Spring WebFlux ({@code docs/PLAN-v2.md} §5.2): each application request publishes one
 * {@code HTTP} event when its filter chain completes, and its blocking JDBC on a Reactor scheduler publishes
 * {@code SQL} events carrying the same request id, so the aggregates fold the statements into the route.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/runtime-journal/application-bootui.properties"
        })
class WebFluxRuntimeJournalTest {

    @LocalServerPort
    int port;

    @Autowired
    RuntimeJournal journal;

    @Autowired
    JournalAggregates aggregates;

    @Test
    void cacheAndExceptionEventsFoldIntoTheRouteThatProducedThem() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        assertThat(probe.get("/api/greetings/bootui").status()).isEqualTo(200);
        assertThat(probe.get("/api/sample/boom").status()).isEqualTo(500);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        assertThat(aggregates.snapshot().routes())
                .anySatisfy(route -> {
                    assertThat(route.route()).startsWith("GET /api/greetings/");
                    assertThat(route.childCounts()).containsKey(JournalSource.CACHE);
                })
                .anySatisfy(route -> {
                    assertThat(route.route()).isEqualTo("GET /api/sample/boom");
                    assertThat(route.childCounts()).containsKey(JournalSource.EXCEPTION);
                    assertThat(route.statusClasses().get(4)).isPositive();
                });
    }

    @Test
    void requestsAndTheirSqlReachTheJournalAndFoldIntoTheMatchedRoute() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/notes").status()).isEqualTo(200);
        }
        assertThat(probe.get("/bootui/api/overview").status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        RouteStats route = aggregates.snapshot().routes().stream()
                .filter(candidate -> candidate.route().equals("GET /api/notes"))
                .findFirst()
                .orElseThrow();
        assertThat(route.requests()).isEqualTo(3);
        assertThat(route.statusClasses().get(1)).isEqualTo(3);
        assertThat(route.childCounts().get(JournalSource.SQL)).isGreaterThanOrEqualTo(3);
        assertThat(aggregates.snapshot().routes())
                .extracting(RouteStats::route)
                .noneMatch(name -> name.contains("/bootui"));
    }
}
