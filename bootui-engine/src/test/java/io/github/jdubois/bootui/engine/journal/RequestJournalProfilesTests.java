package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestTimelineItemDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequestJournalProfilesTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start(),
            false);
    private final JournalAggregates aggregates = new JournalAggregates();

    RequestJournalProfilesTests() {
        journal.addListener(aggregates);
    }

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aRequestsWorkIsPlacedAtItsStartOnOneTimelineWithItsResourcesPausesAndTouchedResources() {
        offer(gc("G1 Young Generation", 8, 1_010, 3));
        offer(child("r1", JournalSource.CONNECTION, 1_002, 20_000_000, new ConnectionPayload("orders", 500_000, 2)));
        offer(child(
                "r1",
                JournalSource.SQL,
                1_012,
                4_000_000,
                new SqlPayload("select * from orders o join lines l on l.o = o.id", null, "orders", false)));
        offer(child(
                "r1",
                JournalSource.TRANSACTION,
                1_001,
                25_000_000,
                new TransactionPayload("OrderService.place", true)));
        offer(child("r1", JournalSource.CACHE, 1_015, -1, new CachePayload("prices", "MISS")));
        offer(child(
                "r1", JournalSource.LOG, 1_020, -1, new LogPayload("com.acme.Orders", "WARN", "Order {} slow", null)));
        offer(child(
                "r1",
                JournalSource.MESSAGING,
                1_021,
                -1,
                new MessagingPayload("kafka", true, "orders.placed", false, null)));
        offer(child(
                "r1",
                JournalSource.REST_CLIENT,
                1_030,
                5_000_000,
                new RestClientPayload("GET", "pricing:8443", "/p", 200, "RestClient", false)));
        offer(child(
                "r2",
                JournalSource.SQL,
                1_005,
                1_000_000,
                new SqlPayload("select 1 from other", null, "orders", false)));
        offer(http(
                "r1",
                1_000,
                40_000_000,
                new ResourceUsage(
                        9_000_000,
                        64_000,
                        2,
                        0,
                        null,
                        2,
                        List.of(new GcPauseRange("G1 Young Generation", 7, 9)),
                        false)));
        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("r1");

        assertThat(profile.available()).isTrue();
        assertThat(profile.route()).isEqualTo("GET /api/orders/{id}");
        assertThat(profile.startedAt()).isEqualTo(1_000);
        assertThat(profile.durationMicros()).isEqualTo(40_000);
        assertThat(profile.status()).isEqualTo(201);
        assertThat(profile.timeline())
                .extracting(RequestTimelineItemDto::source, RequestTimelineItemDto::offsetMillis)
                .as("SQL and REST client calls start their duration before their stamp")
                .containsExactly(
                        tuple("transaction", 1L),
                        tuple("connection", 2L),
                        tuple("sql", 8L),
                        tuple("cache", 15L),
                        tuple("log", 20L),
                        tuple("messaging", 21L),
                        tuple("rest-client", 25L));
        RequestTimelineItemDto connection = profile.timeline().get(1);
        assertThat(connection.label()).isEqualTo("Connection held from orders");
        assertThat(connection.detail()).isEqualTo("waited 500 µs, 2 statements");
        assertThat(connection.durationMicros()).isEqualTo(20_000);
        assertThat(connection.threadKind()).isEqualTo("WORKER");
        assertThat(profile.timeline().get(3).durationMicros())
                .as("an instant has no duration")
                .isNull();
        assertThat(profile.resources().availability()).isEqualTo("AVAILABLE");
        assertThat(profile.resources().cpuNanos()).isEqualTo(9_000_000);
        assertThat(profile.gcPauses()).hasSize(2);
        assertThat(profile.gcPauses().get(0)).satisfies(pause -> {
            assertThat(pause.gcId()).isEqualTo(8);
            assertThat(pause.retained()).isTrue();
            assertThat(pause.offsetMillis()).isEqualTo(10);
            assertThat(pause.pauseMillis()).isEqualTo(3);
            assertThat(pause.cause()).isEqualTo("G1 Evacuation Pause");
        });
        assertThat(profile.gcPauses().get(1).retained())
                .as("collection 9's event is not retained")
                .isFalse();
        assertThat(profile.touched().tables()).containsExactly("orders", "lines");
        assertThat(profile.touched().dataSources()).containsExactly("orders");
        assertThat(profile.touched().transactions()).containsExactly("OrderService.place (rolled back)");
        assertThat(profile.touched().caches()).containsExactly("prices (MISS)");
        assertThat(profile.touched().messages()).containsExactly("orders.placed (kafka)");
        assertThat(profile.touched().restCalls()).containsExactly("pricing:8443");
        assertThat(profile.touched().logTemplates()).containsExactly("Order {} slow");
        assertThat(profile.notes()).isEmpty();
    }

    @Test
    void theRouteComparisonSaysWhereTheRequestStandsOnceTheRouteHasEnoughRequests() {
        for (int i = 0; i < 4; i++) {
            offer(http("w" + i, 1_000, 10_000_000L * (i + 1), null));
        }
        offer(http("slow", 1_000, 200_000_000, null));
        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("slow");

        assertThat(profile.routeComparison()).satisfies(comparison -> {
            assertThat(comparison.route()).isEqualTo("GET /api/orders/{id}");
            assertThat(comparison.requests()).isEqualTo(5);
            assertThat(comparison.durationMicros()).isEqualTo(200_000);
            assertThat(comparison.p50Micros()).isBetween(28_000L, 32_000L);
            assertThat(comparison.standing()).isIn("ABOVE_P50", "ABOVE_P95");
        });
        assertThat(profiles(null).profile("w0").routeComparison().standing()).isEqualTo("AT_OR_BELOW_P50");
        assertThat(profile.resources()).isNull();
        assertThat(profile.notes()).singleElement().asString().contains("resources source");
    }

    @Test
    void aRouteWithFewRequestsGetsNoStandingAndADisabledPanelsRowsAreLeftOut() {
        offer(child(
                "r1", JournalSource.SQL, 1_002, 1_000_000, new SqlPayload("select * from orders", null, "db", false)));
        offer(http("r1", 1_000, 5_000_000, null));
        journal.dispatchPending();

        RequestJournalProfileDto profile =
                profiles(panel -> !panel.equals(BootUiPanels.SQL_TRACE)).profile("r1");

        assertThat(profile.routeComparison().standing()).isNull();
        assertThat(profile.routeComparison().minimumRequests())
                .isEqualTo(RequestJournalProfiles.MINIMUM_ROUTE_REQUESTS);
        assertThat(profile.timeline()).isEmpty();
        assertThat(profile.touched().tables()).isEmpty();
    }

    @Test
    void aiCallsJoinTheRequestRecordedWithTheirTraceIdOnItsTimelineAndTouchedModels() {
        offer(ai("trace-1", 1_004, new AiPayload("chat", "openai", "gpt-4o", 1200L, 300L, "length", false)));
        offer(ai("trace-1", 1_006, new AiPayload("embeddings", "openai", "text-embedding-3", 40L, null, null, false)));
        offer(ai("trace-2", 1_005, new AiPayload("chat", "openai", "gpt-4o-mini", 1L, 1L, "stop", false)));
        offer(traced(http("r1", 1_000, 50_000_000, null), "trace-1"));
        offer(traced(http("r2", 1_000, 50_000_000, null), "trace-2"));
        offer(traced(http("r3", 1_000, 50_000_000, null), "trace-2"));
        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("r1");

        assertThat(profile.timeline())
                .extracting(
                        RequestTimelineItemDto::source,
                        RequestTimelineItemDto::label,
                        RequestTimelineItemDto::offsetMillis,
                        RequestTimelineItemDto::severity)
                .containsExactly(
                        tuple("ai", "chat gpt-4o (openai)", 4L, "WARN"),
                        tuple("ai", "embeddings text-embedding-3 (openai)", 6L, "OK"));
        assertThat(profile.timeline().get(0).detail())
                .isEqualTo("1200 input tokens · 300 output tokens · finish length");
        assertThat(profile.touched().models())
                .containsExactlyInAnyOrder("gpt-4o (openai)", "text-embedding-3 (openai)");
        assertThat(profiles(null).profile("r2").timeline())
                .as("a trace two requests share names neither")
                .isEmpty();
        assertThat(profiles(panel -> !panel.equals(BootUiPanels.AI))
                        .profile("r1")
                        .timeline())
                .as("the AI panel's policy holds")
                .isEmpty();
    }

    @Test
    void anUnknownRequestOrADisabledJournalSaysWhy() {
        assertThat(profiles(null).profile("missing").unavailableReason()).contains("does not retain request missing");
        assertThat(profiles(null).profile(" ").available()).isFalse();
        assertThat(new RequestJournalProfiles(null, null, 1_000, 5, null)
                        .profile("r1")
                        .unavailableReason())
                .isEqualTo(RequestJournalProfiles.DISABLED);
    }

    private RequestJournalProfiles profiles(Predicate<String> panelEnabled) {
        return new RequestJournalProfiles(journal, aggregates, 1_000, 5, panelEnabled);
    }

    private void offer(RuntimeEvent event) {
        journal.offer(event);
    }

    private static RuntimeEvent http(String requestId, long start, long nanos, ResourceUsage usage) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                nanos,
                CorrelationContext.forRequest(requestId),
                "http-1",
                ThreadKind.WORKER,
                false,
                new HttpPayload("GET", "/api/orders/42", "/api/orders/{id}", null, 201, usage));
    }

    private static RuntimeEvent ai(String traceId, long epochMillis, AiPayload payload) {
        return new RuntimeEvent(
                JournalSource.AI, epochMillis, 1_000_000, null, null, traceId, null, null, null, false, payload);
    }

    private static RuntimeEvent traced(RuntimeEvent event, String traceId) {
        return new RuntimeEvent(
                event.source(),
                event.epochMillis(),
                event.durationNanos(),
                event.requestId(),
                event.executionId(),
                traceId,
                event.spanId(),
                event.thread(),
                event.threadKind(),
                event.failedOrSlow(),
                event.payload());
    }

    private static RuntimeEvent child(
            String requestId, JournalSource source, long epochMillis, long nanos, RuntimeEventPayload payload) {
        return RuntimeEvent.of(
                source,
                epochMillis,
                nanos,
                CorrelationContext.forRequest(requestId),
                "worker-1",
                ThreadKind.WORKER,
                false,
                payload);
    }

    private static RuntimeEvent gc(String collector, long id, long epochMillis, long millis) {
        return new RuntimeEvent(
                JournalSource.GC,
                epochMillis,
                millis * 1_000_000,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                new GcPayload(collector, id, "end of minor GC", "G1 Evacuation Pause", true, 2, 1));
    }
}
