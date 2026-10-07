package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RequestHandoffDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestOrmDto;
import io.github.jdubois.bootui.core.dto.RequestTimelineItemDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                1_008,
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
                1_025,
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
                .as("every source stamps when its work started, so the timeline places each item at its stamp")
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
    void anAiCallJoinsOnlyTheRequestSharingItsTraceThatWasRunningWhenItStarted() {
        // r1 made the first call and was then evicted; r2 shares its trace and made the second one.
        offer(ai("trace-1", 1_010, new AiPayload("chat", "openai", "gpt-4o", 1L, 1L, "stop", false)));
        offer(traced(http("r2", 2_000, 50_000_000, null), "trace-1"));
        offer(ai("trace-1", 2_020, new AiPayload("chat", "openai", "gpt-4o-mini", 1L, 1L, "stop", false)));
        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("r2");

        assertThat(profile.timeline())
                .extracting(RequestTimelineItemDto::label, RequestTimelineItemDto::offsetMillis)
                .containsExactly(tuple("chat gpt-4o-mini (openai)", 20L));
        assertThat(profile.touched().models()).containsExactly("gpt-4o-mini (openai)");
    }

    @Test
    void anAiCallWhoseTraceLostARequestToEvictionJoinsNoRetainedRequest() {
        RuntimeJournal small = new RuntimeJournal(
                new RuntimeJournalSettings(true, 4, 10_000_000, 100, 50, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        try {
            // A failed call keeps its reserved share; r1, which made it, is routine and is evicted first.
            small.offer(new RuntimeEvent(
                    JournalSource.AI,
                    1_010,
                    1_000_000,
                    null,
                    null,
                    "trace-1",
                    null,
                    null,
                    true,
                    new AiPayload("chat", "openai", "gpt-4o", 1L, null, null, true)));
            small.offer(traced(http("r1", 1_000, 50_000_000, null), "trace-1"));
            small.offer(traced(http("r2", 1_005, 100_000_000, null), "trace-1"));
            small.offer(http("r3", 2_000, 1_000_000, null));
            small.offer(http("r4", 3_000, 1_000_000, null));
            small.dispatchPending();

            assertThat(small.entries())
                    .extracting(entry -> entry.event().requestId())
                    .doesNotContain("r1");
            assertThat(small.evictedARequestOf("trace-1")).isTrue();
            assertThat(new RequestJournalProfiles(small, null, 1_000, 5, null)
                            .profile("r2")
                            .timeline())
                    .as("r1, which shared its trace and contained it, may have made it")
                    .isEmpty();

            small.clear();
            assertThat(small.evictedARequestOf("trace-1")).isFalse();
        } finally {
            small.close();
        }
    }

    @Test
    void hibernateSessionsAreSummedInAnOrmBlockAndTheirFlushesPlacedOnTheTimeline() {
        offer(child(
                "r1",
                JournalSource.ORM,
                1_002,
                30_000_000,
                new OrmPayload(
                        null,
                        5,
                        4_000_000,
                        1,
                        100_000,
                        1,
                        300_000,
                        2,
                        600_000,
                        2,
                        40,
                        1,
                        2,
                        3,
                        List.of(
                                new OrmPayload.Flush(3_000_000, 200_000, true, 12),
                                new OrmPayload.Flush(20_000_000, 300_000, false, 40)))));
        offer(child(
                "r1",
                JournalSource.ORM,
                1_030,
                1_000_000,
                new OrmPayload(null, 1, 500_000, 1, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0)));
        offer(http("r1", 1_000, 40_000_000, null));
        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("r1");

        assertThat(profile.orm()).isEqualTo(new RequestOrmDto(2, 6, 4_500, 2, 1, 2, 300, 600, 2, 40, 1, 2, 3));
        assertThat(profile.timeline())
                .filteredOn(item -> item.label().contains("lush"))
                .extracting(
                        RequestTimelineItemDto::label,
                        RequestTimelineItemDto::offsetMillis,
                        RequestTimelineItemDto::durationMicros)
                .containsExactly(tuple("Auto-flush before a query", 5L, 200L), tuple("Flush", 22L, 300L));
        assertThat(profiles(null).profile("r1").orm()).isNotNull();
    }

    @Test
    void aRequestWithoutHibernateSessionsHasNoOrmBlock() {
        offer(http("r1", 1_000, 40_000_000, null));
        journal.dispatchPending();

        assertThat(profiles(null).profile("r1").orm()).isNull();
    }

    @Test
    void anUnknownRequestOrADisabledJournalSaysWhy() {
        assertThat(profiles(null).profile("missing").unavailableReason()).contains("execution missing");
        assertThat(profiles(null).profile(" ").available()).isFalse();
        assertThat(new RequestJournalProfiles(null, null, 1_000, 5, null)
                        .profile("r1")
                        .unavailableReason())
                .isEqualTo(RequestJournalProfiles.DISABLED);
    }

    @Test
    void scheduledAndConsumedMessageExecutionsOpenByIdWithoutExposingDisabledSources() {
        CorrelationContext scheduled = CorrelationContext.forExecution("scheduled-1");
        CorrelationContext consumer = CorrelationContext.forExecution("message-1");
        offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                1_000,
                25_000_000,
                scheduled,
                "scheduler",
                ThreadKind.WORKER,
                false,
                new ScheduledPayload("com.example.Cleanup.run", null)));
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_005,
                2_000_000,
                scheduled,
                "scheduler",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select * from orders", null, "db", false)));
        CorrelationContext handoff = CorrelationContext.forExecution("async-job");
        offer(RuntimeEvent.of(
                JournalSource.AGENT_EXECUTORS,
                1_010,
                5_000_000,
                handoff,
                "pool-1",
                ThreadKind.WORKER,
                false,
                new AsyncHandoffPayload(
                        "async-job",
                        "scheduled-1",
                        "CleanupTask",
                        "Executor.execute",
                        1_009,
                        1_000_000,
                        null,
                        false,
                        null,
                        false,
                        0L,
                        false)));
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_012,
                1_000_000,
                handoff,
                "pool-1",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select * from archived_orders", null, "db", false)));
        offer(RuntimeEvent.of(
                JournalSource.MESSAGING,
                2_000,
                30_000_000,
                consumer,
                "listener",
                ThreadKind.WORKER,
                false,
                new MessagingPayload("kafka", false, "orders", false, null)));
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                2_004,
                1_000_000,
                consumer,
                "listener",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select * from customers", null, "db", false)));
        journal.dispatchPending();

        RequestJournalProfileDto job = profiles(null).profile("scheduled-1");
        assertThat(job.available()).isTrue();
        assertThat(job.route()).isEqualTo("Scheduled: com.example.Cleanup.run");
        assertThat(job.status()).isNull();
        assertThat(job.routeComparison()).isNull();
        assertThat(job.timeline())
                .extracting(RequestTimelineItemDto::source)
                .containsExactly("sql", "agent.executors", "sql");
        assertThat(job.touched().tables()).containsExactly("orders", "archived_orders");
        assertThat(job.handoffs()).hasSize(1);
        assertThat(profiles(panel -> !panel.equals(BootUiPanels.SCHEDULED))
                        .profile("scheduled-1")
                        .available())
                .isFalse();
        offer(http("r-without-exchanges-panel", 3_000, 1_000_000, null));
        journal.dispatchPending();
        RequestJournalProfileDto hidden =
                profiles(panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES)).profile("r-without-exchanges-panel");
        assertThat(hidden.available())
                .as("a request's profile is its HTTP exchange, so it follows the HTTP Exchanges panel")
                .isFalse();
        assertThat(hidden.unavailableReason()).contains("http-exchanges").contains("r-without-exchanges-panel");
        assertThat(hidden.timeline()).isEmpty();
        assertThat(profiles(null).profile("r-without-exchanges-panel").available())
                .isTrue();

        RequestJournalProfileDto message = profiles(null).profile("message-1");
        assertThat(message.available()).isTrue();
        assertThat(message.route()).isEqualTo("Message: orders");
        assertThat(message.timeline())
                .extracting(RequestTimelineItemDto::source)
                .containsExactly("sql");
        assertThat(message.touched().tables()).containsExactly("customers");
    }

    @Test
    void aRequestsHandoffsCarryWhatTheyDidAndLateOnesAreOnlyCounted() {
        CorrelationContext async1 = CorrelationContext.forRequest("r1").withExecutionId("async-1");
        CorrelationContext async2 = CorrelationContext.forRequest("r1").withExecutionId("async-2");
        CorrelationContext async3 = CorrelationContext.forRequest("r1").withExecutionId("async-3");
        CorrelationContext async4 = CorrelationContext.forRequest("r1").withExecutionId("async-4");
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_050,
                1_000_000,
                async1,
                "pool-1-thread-1",
                null,
                false,
                new SqlPayload("insert into audit values (?)", null, "orders", false)));
        offer(RuntimeEvent.of(
                JournalSource.REST_CLIENT,
                1_060,
                1_000_000,
                async1,
                "pool-1-thread-1",
                null,
                false,
                new RestClientPayload("POST", "audit:8080", "/events", 202, "RestClient", false)));
        offer(handoff(async1, 1_010, 90, null, null, false, "java.lang.IllegalStateException"));
        offer(handoff(async2, 1_005, 5, false, 0L, false, null));
        // Starts during the request and runs ten minutes: shown capped, and its statement past max-handoff after the
        // handoff started is left out.
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_000 + 600_000,
                1_000_000,
                async3,
                "pool-1-thread-2",
                null,
                false,
                new SqlPayload("delete from carts", null, "orders", false)));
        offer(handoff(async3, 1_020, 600_000, true, 599_000_000L, true, null));
        // Starts more than max-handoff after the request ended: only counted, and neither it nor its work is drawn.
        offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_040 + 400_010,
                1_000_000,
                async4,
                "pool-1-thread-2",
                null,
                false,
                new SqlPayload("delete from wishlists", null, "orders", false)));
        offer(handoff(async4, 1_040 + 400_000, 20, true, 20_000L, false, null));
        offer(http("r1", 1_000, 40_000_000, null));

        journal.dispatchPending();

        RequestJournalProfileDto profile = profiles(null).profile("r1");

        assertThat(profile.handoffs())
                .extracting(
                        RequestHandoffDto::executionId,
                        RequestHandoffDto::afterResponse,
                        RequestHandoffDto::sqlCount,
                        RequestHandoffDto::restClientCount,
                        RequestHandoffDto::failed)
                .containsExactly(
                        tuple("async-2", false, 0, 0, false),
                        tuple("async-1", true, 1, 1, true),
                        tuple("async-3", true, 0, 0, false));
        assertThat(profile.handoffs().get(2).capped()).isTrue();
        RequestHandoffDto async = profile.handoffs().get(1);
        assertThat(async.thread()).isEqualTo("pool-1-thread-1");
        assertThat(async.startOffsetMicros()).isEqualTo(10_000);
        assertThat(async.durationMicros()).isEqualTo(90_000);
        assertThat(async.queuedMicros()).isEqualTo(2_000);
        assertThat(async.afterResponseMicros())
                .as("after the request's end, its response start unknown")
                .isEqualTo(60_000);
        assertThat(async.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(profile.lateHandoffs()).isEqualTo(1);
        assertThat(profile.touched().tables()).doesNotContain("carts", "wishlists");
        assertThat(profile.notes())
                .as("the late count is the profile's lateHandoffs, shown once by the panel")
                .noneMatch(note -> note.contains("max-handoff"));
        assertThat(profile.timeline())
                .filteredOn(item -> item.source().equals("agent.executors"))
                .as("the late handoff is counted, not drawn")
                .hasSize(3)
                .allMatch(item -> item.offsetMillis() < 400_000);

        JournalActivityFeed.Feed feed = new JournalActivityFeed(1_000, 5, null)
                .render(
                        journal.entries(),
                        journal::eventId,
                        journal.run().id(),
                        JournalActivityFeed.Filter.NONE,
                        0,
                        JournalRowDetails.NONE,
                        trace -> false,
                        List.of(
                                new RunningHandoffs.Running(
                                        "r1", "async-9", "e0", null, "pool-1-thread-3", 1_030, "Task", "hook"),
                                new RunningHandoffs.Running(
                                        "unknown", "async-8", null, null, "pool-1-thread-4", 1_030, "Task", "hook")));
        assertThat(feed.entries())
                .filteredOn(entry -> entry.type().equals(JournalActivityFeed.TYPE_ASYNC))
                .extracting(ActivityEntryDto::id, ActivityEntryDto::parentId, ActivityEntryDto::badges)
                .contains(
                        tuple("running:async-9", "r1", List.of("RUNNING", "AFTER_RESPONSE")),
                        tuple(journal.eventId(asyncEntry(1_010)), "r1", List.of("AFTER_RESPONSE")),
                        tuple(journal.eventId(asyncEntry(1_020)), "r1", List.of("AFTER_RESPONSE", "CAPPED")))
                .noneMatch(row -> "running:async-8".equals(row.toList().get(0)));
        assertThat(feed.typeCounts()).containsEntry(JournalActivityFeed.TYPE_ASYNC, 5);
    }

    /**
     * A waited-for task's handoff can close just after its handler answered, since the JDK releases the handler before
     * the task's run returns: its confirmed body completion decides, never that race. A late result-publication tail
     * counts only by its own late I/O, and a handoff without body evidence keeps its run's end.
     */
    @Test
    void aHandoffIsAfterTheResponseByItsBodyNotByItsCloseRacingTheResponse() {
        CorrelationContext waited = CorrelationContext.forRequest("r1").withExecutionId("async-1");
        CorrelationContext later = CorrelationContext.forRequest("r1").withExecutionId("async-2");
        CorrelationContext tail = CorrelationContext.forRequest("r1").withExecutionId("async-3");
        CorrelationContext unconfirmed = CorrelationContext.forRequest("r1").withExecutionId("async-4");
        CorrelationContext quietTail = CorrelationContext.forRequest("r1").withExecutionId("async-5");
        CorrelationContext failedTail = CorrelationContext.forRequest("r1").withExecutionId("async-6");
        long responseAt = 1_030_000L;
        offer(sql(waited, 1_010, 1_000_000));
        // Its body ended before the response; its handoff closed 50 µs after it.
        offer(bodyHandoff(waited, 1_010, 20_100_000, true, 50L, false, 0L, responseAt));
        offer(sql(later, 1_020, 1_000_000));
        offer(bodyHandoff(later, 1_020, 210_000_000, true, 200_000L, true, 199_000L, responseAt));
        // Its body ended before the response, then a dependent stage it published to ran SQL 15 ms after it.
        offer(sql(tail, 1_045, 1_000_000));
        offer(bodyHandoff(tail, 1_015, 40_000_000, true, 25_000L, false, 0L, responseAt));
        offer(handoff(unconfirmed, 1_025, 10, true, 5_000L, false, null));
        // Its body ended before the response and its tail ran no I/O: only the close raced the response.
        offer(bodyHandoff(quietTail, 1_015, 40_000_000, true, 25_000L, false, 0L, responseAt));
        // Its body ended before the response, then its done() callback threw after it.
        offer(bodyHandoff(failedTail, 1_015, 40_000_000, true, 25_000L, false, 0L, responseAt, "IllegalStateException"));
        offer(http("r1", 1_000, 40_000_000, null));

        journal.dispatchPending();

        assertThat(profiles(null).profile("r1").handoffs())
                .extracting(
                        RequestHandoffDto::executionId,
                        RequestHandoffDto::afterResponse,
                        RequestHandoffDto::afterResponseMicros)
                .containsExactlyInAnyOrder(
                        tuple("async-1", false, 0L),
                        tuple("async-2", true, 199_000L),
                        tuple("async-3", true, 25_000L),
                        tuple("async-4", true, 5_000L),
                        tuple("async-5", false, 0L),
                        tuple("async-6", true, 25_000L));

        JournalActivityFeed.Feed feed = new JournalActivityFeed(1_000, 5, null)
                .render(journal.entries(), journal::eventId, journal.run().id(), JournalActivityFeed.Filter.NONE, 0);
        assertThat(feed.entries())
                .filteredOn(entry -> entry.type().equals(JournalActivityFeed.TYPE_ASYNC))
                .extracting(ActivityEntryDto::id, ActivityEntryDto::badges)
                .containsExactlyInAnyOrder(
                        tuple(journal.eventId(asyncEntry("async-1")), List.of()),
                        tuple(journal.eventId(asyncEntry("async-2")), List.of("AFTER_RESPONSE")),
                        tuple(journal.eventId(asyncEntry("async-3")), List.of("AFTER_RESPONSE")),
                        tuple(journal.eventId(asyncEntry("async-4")), List.of("AFTER_RESPONSE")),
                        tuple(journal.eventId(asyncEntry("async-5")), List.of()),
                        tuple(journal.eventId(asyncEntry("async-6")), List.of("AFTER_RESPONSE")));
        // Captured one event per batch, as recorded: the tail's statement is in an earlier batch than its handoff.
        JournalActivityFeed capture = new JournalActivityFeed(1_000, 5, null);
        Map<String, Map<String, Integer>> pendingSelects = new HashMap<>();
        Map<String, Long> pendingWorkEnds = new HashMap<>();
        List<String> captured = new ArrayList<>();
        List<JournalEntry> oldestFirst = new ArrayList<>(journal.entries());
        Collections.reverse(oldestFirst);
        for (JournalEntry entry : oldestFirst) {
            capture
                    .renderForCapture(List.of(entry), journal::eventId, pendingSelects, pendingWorkEnds, count -> {})
                    .stream()
                    .filter(row -> row.type().equals(JournalActivityFeed.TYPE_ASYNC))
                    .filter(row -> row.badges().contains("AFTER_RESPONSE"))
                    .forEach(row -> captured.add(row.id()));
        }
        assertThat(captured)
                .as("captured batch by batch, it decides as the live feed does")
                .containsExactlyInAnyOrder(
                        journal.eventId(asyncEntry("async-2")),
                        journal.eventId(asyncEntry("async-3")),
                        journal.eventId(asyncEntry("async-4")),
                        journal.eventId(asyncEntry("async-6")));
        assertThat(pendingWorkEnds).as("each handoff's entry is forgotten once it renders").isEmpty();
    }

    private JournalEntry asyncEntry(String executionId) {
        return journal.entries().stream()
                .filter(entry -> entry.event().payload() instanceof AsyncHandoffPayload handoff
                        && executionId.equals(handoff.executionId()))
                .findFirst()
                .orElseThrow();
    }

    private static RuntimeEvent sql(CorrelationContext context, long start, long nanos) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                start,
                nanos,
                context,
                "pool-1-thread-1",
                null,
                false,
                new SqlPayload("select count(*) from orders", null, "orders", false));
    }

    private static RuntimeEvent bodyHandoff(
            CorrelationContext context,
            long start,
            long nanos,
            boolean afterResponse,
            long afterResponseMicros,
            boolean bodyAfterResponse,
            long bodyAfterResponseMicros,
            long responseAtMicros) {
        return bodyHandoff(
                context,
                start,
                nanos,
                afterResponse,
                afterResponseMicros,
                bodyAfterResponse,
                bodyAfterResponseMicros,
                responseAtMicros,
                null);
    }

    private static RuntimeEvent bodyHandoff(
            CorrelationContext context,
            long start,
            long nanos,
            boolean afterResponse,
            long afterResponseMicros,
            boolean bodyAfterResponse,
            long bodyAfterResponseMicros,
            long responseAtMicros,
            String tailFailure) {
        return RuntimeEvent.of(
                JournalSource.AGENT_EXECUTORS,
                start,
                nanos,
                context,
                "pool-1-thread-1",
                null,
                tailFailure != null,
                new AsyncHandoffPayload(
                        context.executionId(),
                        null,
                        "java.util.concurrent.FutureTask",
                        "ThreadPoolExecutor.runWorker",
                        start,
                        0,
                        null,
                        tailFailure != null,
                        tailFailure,
                        afterResponse,
                        afterResponseMicros,
                        false,
                        bodyAfterResponse,
                        bodyAfterResponseMicros,
                        responseAtMicros,
                        tailFailure == null ? null : Boolean.TRUE));
    }

    private JournalEntry asyncEntry(long start) {
        return journal.entries().stream()
                .filter(entry -> entry.event().payload() instanceof AsyncHandoffPayload
                        && entry.event().epochMillis() == start)
                .findFirst()
                .orElseThrow();
    }

    private static RuntimeEvent handoff(
            CorrelationContext context,
            long start,
            long millis,
            Boolean afterResponse,
            Long afterResponseMicros,
            boolean capped,
            String exceptionClass) {
        return RuntimeEvent.of(
                JournalSource.AGENT_EXECUTORS,
                start,
                millis * 1_000_000,
                context,
                "pool-1-thread-1",
                null,
                exceptionClass != null,
                new AsyncHandoffPayload(
                        context.executionId(),
                        null,
                        "java.util.concurrent.FutureTask",
                        "ThreadPoolExecutor.runWorker",
                        start - 2,
                        2_000_000,
                        null,
                        exceptionClass != null,
                        exceptionClass,
                        afterResponse,
                        afterResponseMicros,
                        capped));
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
                JournalSource.AI, epochMillis, 1_000_000, null, null, traceId, null, null, false, payload);
    }

    private static RuntimeEvent traced(RuntimeEvent event, String traceId) {
        return new RuntimeEvent(
                event.source(),
                event.epochMillis(),
                event.durationNanos(),
                event.requestId(),
                event.executionId(),
                traceId,
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
                false,
                new GcPayload(collector, id, "end of minor GC", "G1 Evacuation Pause", true, 2, 1));
    }
}
