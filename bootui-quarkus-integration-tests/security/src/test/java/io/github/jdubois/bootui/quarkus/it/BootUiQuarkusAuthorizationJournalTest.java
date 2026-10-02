package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code authorization} source on Quarkus ({@code docs/PLAN-v2.md} §5.18): each security check fires an
 * authorization event, recorded as a decision that joins its request by id, with whether the caller was anonymous.
 */
@QuarkusTest
class BootUiQuarkusAuthorizationJournalTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    RuntimeJournal journal;

    @Inject
    JournalAggregates aggregates;

    @Test
    void eachRolesAllowedCheckIsADecisionJoinedToItsRequestWithTheCallersAuthentication() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        String admin = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

        assertThat(probe.get("/secure", Map.of("Authorization", admin)).status())
                .isEqualTo(200);
        assertThat(probe.get("/secure").status()).isEqualTo(401);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        List<RuntimeEvent> decisions = journal.entries().stream()
                .map(entry -> entry.event())
                .filter(event -> event.source() == JournalSource.AUTHORIZATION)
                .toList();
        assertThat(decisions).anySatisfy(event -> {
            AuthorizationPayload decision = (AuthorizationPayload) event.payload();
            assertThat(decision.granted()).isTrue();
            assertThat(decision.authentication()).isEqualTo(AuthorizationPayload.AUTHENTICATED);
            assertThat(decision.authorities()).isEqualTo(1);
            assertThat(event.requestId()).isNotBlank();
        });
        assertThat(decisions).anySatisfy(event -> {
            AuthorizationPayload decision = (AuthorizationPayload) event.payload();
            assertThat(decision.granted()).isFalse();
            assertThat(decision.authentication()).isEqualTo(AuthorizationPayload.ANONYMOUS);
        });
        assertThat(aggregates.snapshot().routes())
                .filteredOn(route -> route.route().endsWith("/secure"))
                .anySatisfy(route -> {
                    assertThat(route.authorization().authenticated()).isPositive();
                    assertThat(route.authorization().denied()).isPositive();
                });
    }
}
