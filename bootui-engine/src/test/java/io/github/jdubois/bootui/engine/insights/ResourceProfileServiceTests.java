package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileRouteDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.resources.JfrProfiler;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResourceProfileServiceTests {

    private static volatile long sink;

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aSessionReportsItsSamplesByTheRouteTheJournalNamesForEachRequest() throws Exception {
        assumeThat(JfrProfiler.unavailableReason()).as("JFR is available").isNull();
        AtomicBoolean httpExchanges = new AtomicBoolean(true);
        ResourceProfileService service = new ResourceProfileService(
                journal,
                null,
                Duration.ofMinutes(1),
                panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES) || httpExchanges.get());
        assertThat(service.status().state())
                .as("reading the status starts nothing")
                .isIn("IDLE", "COMPLETED");

        RuntimeResourceProfileDto running = service.start();
        assertThat(running.state()).isEqualTo("RUNNING");
        assertThat(running.maxDurationSeconds()).isEqualTo(60);
        assertThat(running.endsAt() - running.startedAt()).isEqualTo(60_000);
        String recorded = RequestIds.next();
        String evicted = RequestIds.next();
        run(recorded);
        run(evicted);
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                System.currentTimeMillis(),
                600_000_000L,
                CorrelationContext.forRequest(recorded),
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/api/report/7", "/api/report/{id}", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeResourceProfileDto done = service.stop();

        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.requests()).isEqualTo(2);
        assertThat(done.cpuSamples()).isPositive();
        assertThat(done.routes())
                .extracting(RuntimeResourceProfileRouteDto::route)
                .containsExactlyInAnyOrder("GET /api/report/{id}", ResourceProfileService.NOT_RETAINED);
        RuntimeResourceProfileRouteDto report = done.routes().stream()
                .filter(route -> route.route().equals("GET /api/report/{id}"))
                .findFirst()
                .orElseThrow();
        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.cpuSamples()).isPositive();
        assertThat(report.hotFrames()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(done.limitations()).anyMatch(limitation -> limitation.contains("not as a measured time"));
        assertThat(service.status().routes())
                .as("the results stay until the next session")
                .isEqualTo(done.routes());

        httpExchanges.set(false);
        RuntimeResourceProfileDto hidden = service.status();
        assertThat(hidden.routes())
                .as("disabling HTTP Exchanges after the session withholds its cached per-route rows")
                .isEmpty();
        assertThat(hidden.routesOmitted()).isZero();
        assertThat(hidden.cpuSamples()).isEqualTo(done.cpuSamples());
        assertThat(hidden.limitations()).contains(ResourceProfileService.ROUTES_HIDDEN);
        httpExchanges.set(true);
        assertThat(service.status().routes()).isEqualTo(done.routes());
    }

    @Test
    void withoutTheResourcesSourceOrTheJournalNoSessionStarts() {
        RuntimeJournal withoutResources =
                new RuntimeJournal(RuntimeJournalSettings.of(true, 1_000, null, 100, "http,sql"), RunIdentity.start());
        try {
            RuntimeResourceProfileDto refused =
                    new ResourceProfileService(withoutResources, null, Duration.ofSeconds(5), null).start();
            assertThat(refused.state()).isEqualTo("UNAVAILABLE");
            assertThat(refused.reason()).isEqualTo(ResourceProfileService.RESOURCES_OFF);
        } finally {
            withoutResources.close();
        }
        RuntimeResourceProfileDto disabled =
                new ResourceProfileService(null, null, Duration.ofSeconds(5), null).status();
        assertThat(disabled.state()).isEqualTo("UNAVAILABLE");
        assertThat(disabled.reason()).isEqualTo(RuntimeInsightsService.DISABLED);
    }

    private static void run(String requestId) throws InterruptedException {
        Thread worker = new Thread(() -> {
            SegmentMeter meter = SegmentMeter.shared();
            meter.begin(requestId);
            meter.switchTo(null);
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
                long end = System.nanoTime() + 400_000_000L;
                long sum = 0;
                while (System.nanoTime() < end) {
                    sum += new byte[256].length + Long.toString(sum).length();
                }
                sink = sum;
            }
            meter.take(requestId);
        });
        worker.start();
        worker.join();
    }
}
