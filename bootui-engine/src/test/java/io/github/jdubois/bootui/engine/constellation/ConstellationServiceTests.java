package io.github.jdubois.bootui.engine.constellation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ConstellationReport;
import io.github.jdubois.bootui.core.dto.PeerNodeDto;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConstellationServiceTests {

    @Test
    void reportIsDisabledWithNoPeersConfigured() {
        ConstellationService service =
                ConstellationService.using(List.of(), Duration.ofSeconds(1), (url, timeout) -> {
                    throw new AssertionError("should not be called with no configured peers");
                });

        ConstellationReport report = service.report();

        assertThat(report.enabled()).isFalse();
        assertThat(report.peers()).isEmpty();
    }

    @Test
    void reportMapsEachConfiguredPeerConcurrently() {
        AtomicInteger calls = new AtomicInteger();
        ConstellationService service = ConstellationService.using(
                List.of("http://localhost:8081", "http://localhost:8082"),
                Duration.ofSeconds(1),
                (url, timeout) -> {
                    calls.incrementAndGet();
                    return new PeerSnapshot(
                            url, true, "peer-app", "spring-boot", "4.1.0", "17", List.of("dev"), null);
                });

        ConstellationReport report = service.report();

        assertThat(report.enabled()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(report.peers())
                .extracting(PeerNodeDto::url)
                .containsExactlyInAnyOrder("http://localhost:8081", "http://localhost:8082");
        assertThat(report.peers()).allSatisfy(peer -> {
            assertThat(peer.reachable()).isTrue();
            assertThat(peer.applicationName()).isEqualTo("peer-app");
            assertThat(peer.platform()).isEqualTo("spring-boot");
            assertThat(peer.activeProfiles()).containsExactly("dev");
        });
    }

    @Test
    void reportDegradesAnUnreachablePeerInsteadOfFailingTheWholeReport() {
        ConstellationService service = ConstellationService.using(
                List.of("http://localhost:9999"),
                Duration.ofSeconds(1),
                (url, timeout) -> {
                    throw new RuntimeException("connection refused");
                });

        ConstellationReport report = service.report();

        assertThat(report.enabled()).isTrue();
        assertThat(report.peers()).hasSize(1);
        PeerNodeDto peer = report.peers().get(0);
        assertThat(peer.reachable()).isFalse();
        assertThat(peer.errorMessage()).isEqualTo("connection refused");
    }
}
