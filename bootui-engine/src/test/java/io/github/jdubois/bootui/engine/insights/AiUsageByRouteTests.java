package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiUsageByRouteTests {

    private static final long MS = 1_000_000;

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aiOperationsAreLinkedToTheirRequestByTraceIdWithAgentLoopsGrowthAndLengthLimitedStops() {
        request(
                "/api/assistant",
                "t1",
                chat(1_000L, 100L, "stop", 0),
                new AiPayload(AiPayload.TOOL, null, null, null, null, null, false),
                chat(2_500L, 150L, "stop", 10),
                chat(4_000L, 4_096L, "length", 20));
        request("/api/assistant", "t2", chat(900L, 80L, "stop", 0));
        request("/api/orders", "t3");
        // An AI span of a trace no retained request carries stays unlinked.
        offerAi("t-unknown", chat(10L, 10L, "stop", 0), 0);

        RuntimeInsightsReportDto report = new RuntimeInsightsService(journal, null, null, null, null).report();
        List<RuntimeObservationDto> usage = report.observations().stream()
                .filter(observation -> observation.kind().equals(AiUsageByRoute.KIND))
                .toList();

        assertThat(usage).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.minimumTier()).isEqualTo("TRACE_ID");
            assertThat(observation.sentence())
                    .isEqualTo("`GET /api/assistant` made 5 AI operations in 2 of 2 requests: 4 model calls, up to 3"
                            + " in one request, a median 40 ms each, 8400 input and 4426 output tokens (reported by 4"
                            + " of 4 calls). Input tokens grew across successive model calls in 1 request, up to 4.0"
                            + " times the first call's. 1 call stopped at the length limit.");
            assertThat(observation.whatToCheck())
                    .hasSize(3)
                    .first()
                    .satisfies(check -> assertThat(check).contains("agent or tool loop"));
            assertThat(observation.exemplarRequestIds()).first().isEqualTo("r1");
        });
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("ai"))
                .singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.byTraceId()).isEqualTo(5);
                    assertThat(coverage.unlinked()).isEqualTo(1);
                    assertThat(coverage.byRequestId()).isZero();
                });
        assertThat(report.coverage())
                .extracting(RuntimeInsightCoverageDto::source)
                .contains("http");
    }

    @Test
    void withoutTracingAiUsageIsNotApplicableRatherThanNone() {
        request("/api/assistant", null);

        RuntimeInsightsReportDto report = new RuntimeInsightsService(journal, null, null, null, null).report();

        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AiUsageByRoute.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
                    assertThat(check.reason()).contains("Without tracing");
                });
    }

    private static AiPayload chat(Long input, Long output, String finishReason, int offset) {
        return new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", input, output, finishReason, false);
    }

    private void request(String path, String traceId, AiPayload... operations) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        journal.offer(new RuntimeEvent(
                JournalSource.HTTP,
                1_000,
                200 * MS,
                requestId,
                null,
                traceId,
                null,
                "http-1",
                null,
                false,
                new HttpPayload("GET", path, path, null, 200)));
        for (int i = 0; i < operations.length; i++) {
            offerAi(traceId, operations[i], i);
        }
        drain();
    }

    private void offerAi(String traceId, AiPayload payload, int order) {
        journal.offer(new RuntimeEvent(
                JournalSource.AI,
                2_000 + order,
                40 * MS,
                null,
                null,
                traceId,
                "s" + order,
                null,
                null,
                payload.failed(),
                payload));
        drain();
    }

    private void drain() {
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
