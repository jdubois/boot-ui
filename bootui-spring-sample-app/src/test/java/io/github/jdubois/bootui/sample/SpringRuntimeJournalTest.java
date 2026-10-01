package io.github.jdubois.bootui.sample;

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
 * The runtime journal on Spring MVC ({@code docs/PLAN-v2.md} §5.2): each application request publishes one
 * {@code HTTP} event with the template Spring MVC matched, and its SQL publishes {@code SQL} events carrying the same
 * request id, so the aggregates fold the statements into the route. BootUI's own requests never enter the journal.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_journal;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/runtime-journal/application-bootui.properties"
        })
class SpringRuntimeJournalTest {

    @LocalServerPort
    int port;

    @Autowired
    RuntimeJournal journal;

    @Autowired
    JournalAggregates aggregates;

    @Test
    void requestsAndTheirSqlReachTheJournalAndFoldIntoTheMatchedRoute() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/sample/product-search?term=console").status())
                    .isEqualTo(200);
        }
        assertThat(probe.get("/bootui/api/overview").status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        RouteStats route = aggregates.snapshot().routes().stream()
                .filter(candidate -> candidate.route().equals("GET /api/sample/product-search"))
                .findFirst()
                .orElseThrow();
        assertThat(route.requests()).isEqualTo(3);
        assertThat(route.statusClasses().get(1)).isEqualTo(3);
        assertThat(route.childCounts().get(JournalSource.SQL)).isGreaterThanOrEqualTo(3);
        assertThat(route.statements()).isNotEmpty();
        assertThat(aggregates.snapshot().routes())
                .extracting(RouteStats::route)
                .noneMatch(name -> name.contains("/bootui"));
        assertThat(journal.status().accepted()).containsKeys(JournalSource.HTTP, JournalSource.SQL);
        assertThat(journal.status().droppedTotal()).isZero();
    }
}
