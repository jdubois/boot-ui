package io.github.jdubois.bootui.engine.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CaughtExceptionRowDto;
import io.github.jdubois.bootui.core.dto.CaughtExceptionsReport;
import io.github.jdubois.bootui.engine.journal.CaughtExceptionPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.ThrowableMarks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The outcome of each caught exception and the honesty rule ({@code docs/PLAN-v2.md} M5-6): positive evidence first,
 * then the handler's shape, and "not rethrown or logged" only when every completeness condition holds; otherwise
 * unknown with its reason, or pending while the request settles.
 */
class CaughtOutcomesTests {

    private static final long NOW = 1_000_000L;
    private static final long START = NOW - 30_000L;
    private static final int EXIT = CaughtOutcomes.FLAG_EXIT_HANDLER;

    private final List<JournalEntry> entries = new ArrayList<>();
    private long sequence;

    @Test
    void aCaughtExceptionNeitherRethrownNorLoggedInACompleteRequestIsNotRethrownOrLogged() {
        request("r1", START, 100);
        caught("r1", START + 10, "swallow", EXIT, 11, "java.lang.IllegalStateException", null);

        CaughtExceptionRowDto row = single(resolve(complete()));

        assertThat(row.notRethrownOrLogged()).isEqualTo(1);
        assertThat(row.unknown()).isZero();
        assertThat(row.finding()).as("one request of a family-less exception").isFalse();
        assertThat(row.route()).isEqualTo("/api/caught");
        assertThat(row.exemplarRequestId()).isEqualTo("r1");
    }

    @Test
    void threeRequestsOrOneForAnIoExceptionMakeAFinding() {
        for (String id : List.of("r1", "r2", "r3")) {
            request(id, START, 100);
            caught(id, START + 10, "swallow", EXIT, id.hashCode(), "java.lang.IllegalStateException", null);
        }
        request("r4", START, 100);
        caught("r4", START + 10, "io", EXIT, 44, "java.io.IOException", "io");

        CaughtExceptionsReport report = resolve(complete());

        assertThat(report.findings()).isEqualTo(2);
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.finding()).isTrue());
    }

    @Test
    void positiveEvidenceIsReadInOrderRethrownReportedLoggedThenTheHandlersShape() {
        request("r1", START, 1_000);
        caught("r1", START + 10, "rethrows", EXIT, 1, "java.lang.IllegalStateException", null);
        thrown("r1", START + 11, "rethrows", EXIT, 1);
        caught("r1", START + 20, "reported", EXIT, 2, "java.lang.IllegalStateException", null);
        add(
                JournalSource.EXCEPTION,
                START + 21,
                "r1",
                "t1",
                new ExceptionPayload("g", "x.Wrapper", "sig", List.of(), ThrowableMarks.ofIdentities(99, 2), false));
        caught("r1", START + 30, "logged", EXIT, 3, "java.lang.IllegalStateException", null);
        add(
                JournalSource.LOG,
                START + 31,
                null,
                "elsewhere",
                new LogPayload("app", "WARN", "failed", "x.Wrapper", ThrowableMarks.ofIdentities(3)));
        caught("r1", START + 40, "handedOn", EXIT | CaughtOutcomes.SHAPE_PASSES_AS_VALUE, 4, "x.E", null);
        caught("r1", START + 50, "interrupted", EXIT | CaughtOutcomes.SHAPE_REINTERRUPTS, 5, "x.E", null);
        caught("r1", START + 60, "replaced", EXIT | CaughtOutcomes.SHAPE_THROWS_NEW, 6, "x.E", null);

        Map<String, CaughtExceptionRowDto> rows = byMethod(resolve(complete()));

        assertThat(rows.get("rethrows").rethrown()).isEqualTo(1);
        assertThat(rows.get("reported").reported()).isEqualTo(1);
        assertThat(rows.get("logged").logged()).isEqualTo(1);
        assertThat(rows.get("handedOn").handedOn()).isEqualTo(1);
        assertThat(rows.get("interrupted").reinterrupted()).isEqualTo(1);
        assertThat(rows.get("replaced").replaced()).isEqualTo(1);
        assertThat(rows.values())
                .allSatisfy(row -> assertThat(row.notRethrownOrLogged()).isZero());
    }

    @Test
    void aMarkFromBeforeTheRequestStartedNeverCounts() {
        add(
                JournalSource.LOG,
                START - 5,
                "r0",
                "t1",
                new LogPayload("app", "ERROR", "earlier", "x.E", ThrowableMarks.ofIdentities(7)));
        request("r1", START, 100);
        caught("r1", START + 10, "swallow", EXIT, 7, "x.E", null);

        assertThat(single(resolve(complete())).notRethrownOrLogged()).isEqualTo(1);
    }

    @Test
    void aWarnOnTheCatchingThreadBeforeItsNextCatchIsLoggedAndOneAfterItIsNot() {
        request("r1", START, 1_000);
        caught("r1", START + 10, "first", EXIT, 1, "x.E", null);
        // A log without the exception, as log.warn(e.getMessage()), in no request: the same thread, before its end.
        add(JournalSource.LOG, START + 12, null, "t1", new LogPayload("app", "WARN", "{}", null, null));
        caught("r1", START + 20, "second", EXIT, 2, "x.E", null);
        caught("r1", START + 30, "third", EXIT, 3, "x.E", null);
        add(JournalSource.LOG, START + 40, "r1", "t1", new LogPayload("app", "INFO", "info", null, null));

        Map<String, CaughtExceptionRowDto> rows = byMethod(resolve(complete()));

        assertThat(rows.get("first").logged()).isEqualTo(1);
        assertThat(rows.get("second").notRethrownOrLogged()).isEqualTo(1);
        assertThat(rows.get("third").notRethrownOrLogged())
                .as("INFO is not a log at WARN or above")
                .isEqualTo(1);
    }

    @Test
    void anEarlierAttemptOfAHandlerWhoseLaterAttemptWasRethrownIsRetried() {
        request("r1", START, 1_000);
        caught("r1", START + 10, "retry", EXIT, 1, "java.io.IOException", "io");
        caught("r1", START + 20, "retry", EXIT, 2, "java.io.IOException", "io");
        thrown("r1", START + 21, "retry", EXIT, 2);

        CaughtExceptionRowDto row = single(resolve(complete()));

        assertThat(row.retried()).isEqualTo(1);
        assertThat(row.rethrown()).isEqualTo(1);
        assertThat(row.finding()).isFalse();
    }

    @Test
    void eachIncompleteConditionIsUnknownWithItsReasonNeverAFinding() {
        assertUnknown(CaughtOutcomes.REASON_CLEARED, context -> with(context, "cleared", START));
        assertUnknown(CaughtOutcomes.REASON_LOST, context -> with(context, "horizon", START + 1));
        assertUnknown(CaughtOutcomes.REASON_LOST, context -> with(context, "lostLog", START + 50));
        assertUnknown(CaughtOutcomes.REASON_LOST, context -> with(context, "agentLost", 0));
        assertUnknown(CaughtOutcomes.REASON_LOSS_UNACCOUNTED, context -> with(context, "unaccounted", 0));
        assertUnknown(CaughtOutcomes.REASON_HANDOFFS, context -> with(context, "noHandoffs", 0));
        assertUnknown(CaughtOutcomes.REASON_AFTER_RESPONSE, context -> with(context, "forgotten", START + 1));
        assertUnknown("a logger bypasses BootUI", context -> with(context, "logGap", 0));
        assertUnknown("Log Tail is hidden", context -> with(context, "logHidden", 0));
        assertUnknown(CaughtOutcomes.REASON_REPORTS, context -> with(context, "noReports", 0));
        assertUnknown(CaughtOutcomes.REASON_BEFORE_MARKS, context -> with(context, "marksSince", START + 1));
    }

    @Test
    void aCatchInTheLastMillisecondOfAFastRequestIsNotWorkAfterTheResponse() {
        // Started at START + 0.9 ms, truncated to START; ran 1.9 ms, rounded down to 1: it really ended at START + 2.8.
        add(JournalSource.HTTP, START, "fast", "t1", http(200, false), 1_900_000L, true);
        caught("fast", START + 2, "swallow", EXIT, 1, "x.E", null);

        assertThat(single(resolve(complete())).notRethrownOrLogged()).isEqualTo(1);
    }

    @Test
    void anotherRequestsCatchOnASharedThreadNeverEndsTheSameThreadLogWindow() {
        request("r1", START, 1_000);
        request("r2", START, 1_000);
        caught("r1", START + 10, "first", EXIT, 1, "x.E", null);
        // Another request's catch on the same event loop, then r1's log of the message, without the exception.
        caught("r2", START + 11, "other", EXIT, 2, "x.E", null);
        add(JournalSource.LOG, START + 12, "r1", "t1", new LogPayload("app", "WARN", "{}", null, null));

        assertThat(byMethod(resolve(complete())).get("first").logged()).isEqualTo(1);
    }

    @Test
    void aRecordWithoutItsThreadOrClassOrWithCountedOccurrencesOfItsRequestIsUnknown() {
        request("r1", START, 100);
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                START + 10,
                "r1",
                null,
                payload(CaughtExceptionPayload.CAUGHT, "unnamed", EXIT, "x.E", null, 1));
        request("r2", START, 100);
        caught("r2", START + 10, "loop", EXIT, 2, "x.E", null);
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                START + 50,
                "r2",
                "t1",
                payload(CaughtExceptionPayload.UNTRACKED, "loop", EXIT, null, null, 0));

        Map<String, CaughtExceptionRowDto> rows = byMethod(resolve(complete()));

        assertThat(rows.get("unnamed").unknownReason()).isEqualTo(CaughtOutcomes.REASON_UNNAMED);
        assertThat(rows.get("loop").unknownReason()).isEqualTo(CaughtOutcomes.REASON_COUNTED);
    }

    @Test
    void anExceptionCapturedFromALogAtAnyLevelIsNeverReadAsLogged() {
        request("r1", START, 100);
        caught("r1", START + 10, "debugLogged", EXIT, 5, "x.E", null);
        // The exception feeder records throwables logged at any level, DEBUG included: only WARN+ log events count.
        add(
                JournalSource.EXCEPTION,
                START + 11,
                "r1",
                "t2",
                new ExceptionPayload("g", "x.E", "sig", List.of(), ThrowableMarks.ofIdentities(5), true));

        assertThat(single(resolve(complete())).notRethrownOrLogged()).isEqualTo(1);
    }

    @Test
    void aWarnOfAnotherExceptionOnTheCatchingThreadIsNeverItsLog() {
        request("r1", START, 1_000);
        caught("r1", START + 10, "swallow", EXIT, 1, "x.E", null);
        add(
                JournalSource.LOG,
                START + 12,
                "r1",
                "t1",
                new LogPayload("app", "WARN", "other", "x.E", ThrowableMarks.ofIdentities(99)));

        assertThat(single(resolve(complete())).notRethrownOrLogged()).isEqualTo(1);
    }

    @Test
    void aWarnWithoutARequestOnASharedEventLoopLeavesItUnknown() {
        request("r1", START, 1_000);
        caught("r1", START + 10, "swallow", EXIT, 1, "x.E", null);
        RuntimeEvent log = new RuntimeEvent(
                JournalSource.LOG,
                START + 12,
                -1,
                null,
                null,
                null,
                "t1",
                io.github.jdubois.bootui.spi.ThreadKind.EVENT_LOOP,
                false,
                new LogPayload("app", "WARN", "{}", null, null));
        entries.add(new JournalEntry(++sequence, log, 100));

        assertThat(single(resolve(complete())).unknownReason()).isEqualTo(CaughtOutcomes.REASON_SHARED_THREAD);
    }

    @Test
    void aHandlerEndingByAThrowIsReplacedOnlyWhenItsRethrowWasObservableAndNothingWasLost() {
        request("r1", START, 100);
        caught("r1", START + 10, "ends", EXIT | CaughtOutcomes.SHAPE_THROWS_NEW, 1, "x.E", null);
        assertThat(single(resolve(complete())).replaced()).isEqualTo(1);
        assertThat(single(resolve(with(complete(), "horizon", START))).unknownReason())
                .isEqualTo(CaughtOutcomes.REASON_LOST);

        entries.clear();
        request("r2", START, 100);
        caught("r2", START + 10, "ends", CaughtOutcomes.SHAPE_THROWS_NEW, 2, "x.E", null);
        assertThat(single(resolve(complete())).unknownReason()).isEqualTo(CaughtOutcomes.REASON_NO_EXIT);
    }

    @Test
    void aLossBeforeTheRequestStartedLeavesItComplete() {
        request("r1", START, 100);
        caught("r1", START + 10, "swallow", EXIT, 1, "x.E", null);

        CaughtOutcomes.Context context = with(with(complete(), "lostLog", START - 1), "horizon", START - 1);

        assertThat(single(resolve(context)).notRethrownOrLogged()).isEqualTo(1);
    }

    @Test
    void workAfterTheResponseAnAsyncRequestOrACancelMakeItsOccurrencesUnknown() {
        request("late", START, 100);
        caught("late", START + 10, "late", EXIT, 1, "x.E", null);
        add(JournalSource.LOG, START + 500, "late", "t2", new LogPayload("app", "WARN", "later", null, null));
        add(JournalSource.HTTP, START, "async", "t1", http(200, true), 100);
        caught("async", START + 10, "async", EXIT, 2, "x.E", null);
        add(JournalSource.HTTP, START, "cancel", "t1", http(0, false), 100);
        caught("cancel", START + 10, "cancel", EXIT, 3, "x.E", null);

        Map<String, CaughtExceptionRowDto> rows = byMethod(resolve(complete()));

        assertThat(rows.get("late").unknownReason()).isEqualTo(CaughtOutcomes.REASON_AFTER_RESPONSE);
        assertThat(rows.get("async").unknownReason()).isEqualTo(CaughtOutcomes.REASON_ASYNC);
        assertThat(rows.get("cancel").unknownReason()).isEqualTo(CaughtOutcomes.REASON_ASYNC);
    }

    @Test
    void anEvictedRecordNoExitHandlerAWrapperOrNoRequestIsUnknown() {
        request("evicted", START, 100);
        caught("evicted", START + 10, "evicted", EXIT, 1, "x.E", null);
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                START + 11,
                "evicted",
                null,
                payload(CaughtExceptionPayload.EVICTED, "other", EXIT, null, null, 9));
        request("noExit", START, 100);
        caught("noExit", START + 10, "noExit", 0, 2, "x.E", null);
        request("wrapper", START, 100);
        caught("wrapper", START + 10, "wrapper", EXIT, 3, "java.util.concurrent.ExecutionException", null);
        caught(null, START + 10, "unowned", EXIT, 4, "x.E", null);

        Map<String, CaughtExceptionRowDto> rows = byMethod(resolve(complete()));

        assertThat(rows.get("evicted").unknownReason()).isEqualTo(CaughtOutcomes.REASON_LOST);
        assertThat(rows.get("noExit").unknownReason()).isEqualTo(CaughtOutcomes.REASON_NO_EXIT);
        assertThat(rows.get("wrapper").unknownReason()).isEqualTo(CaughtOutcomes.REASON_WRAPPER);
        assertThat(rows.get("unowned").unknownReason()).isEqualTo(CaughtOutcomes.REASON_NO_REQUEST);
        assertThat(rows.get("unowned").ownerKind()).isEqualTo("none");
    }

    @Test
    void aDiscardingHandlerNeedsNoExitHandler() {
        request("r1", START, 100);
        caught("r1", START + 10, "discards", CaughtOutcomes.SHAPE_DISCARDS, 1, "x.E", null);

        CaughtExceptionRowDto row = single(resolve(complete()));

        assertThat(row.notRethrownOrLogged()).isEqualTo(1);
        assertThat(row.shapes()).containsExactly("discards");
    }

    @Test
    void anUnsettledOrUndrainedRequestOrOneWithARunningHandoffIsPending() {
        request("recent", NOW - 1_000, 100);
        caught("recent", NOW - 990, "recent", EXIT, 1, "x.E", null);
        caught("running", NOW - 2_000, "running", EXIT, 2, "x.E", null);

        CaughtExceptionsReport report = resolve(complete());

        assertThat(report.settling()).isEqualTo(2);
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.pending()).isEqualTo(1));

        entries.clear();
        request("r1", START, 100);
        caught("r1", START + 10, "x", EXIT, 3, "x.E", null);
        assertThat(single(resolve(with(complete(), "undrained", 0))).pending()).isEqualTo(1);
        assertThat(single(resolve(with(complete(), "handoffRunning", 0))).pending())
                .isEqualTo(1);
    }

    @Test
    void hiddenHttpExchangesKeepsOutcomesButDropsRoutesAndRequestIds() {
        request("r1", START, 100);
        caught("r1", START + 10, "swallow", EXIT, 1, "x.E", null);

        CaughtExceptionRowDto row = single(resolve(with(complete(), "httpHidden", 0)));

        assertThat(row.notRethrownOrLogged()).isEqualTo(1);
        assertThat(row.route()).isNull();
        assertThat(row.exemplarRequestId()).isNull();
    }

    @Test
    void countedOccurrencesJoinTheirSitesRowAndAreNeverResolved() {
        request("r1", START, 100);
        caught("r1", START + 10, "loop", EXIT, 1, "x.E", null);
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                START + 200,
                "r1",
                "t1",
                new CaughtExceptionPayload(
                        CaughtExceptionPayload.UNTRACKED,
                        "com.example.Shop",
                        "loop()V",
                        12,
                        List.of("x/E"),
                        EXIT,
                        null,
                        null,
                        40,
                        null,
                        null,
                        0));

        CaughtExceptionRowDto row = single(resolve(complete()));

        assertThat(row.counted()).isEqualTo(40);
        assertThat(row.occurrences()).isEqualTo(1);
        // Its request's later occurrences were only counted: a final rethrow or log among them would go unseen.
        assertThat(row.unknownReason()).isEqualTo(CaughtOutcomes.REASON_COUNTED);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void assertUnknown(String reason, UnaryOperator<CaughtOutcomes.Context> change) {
        entries.clear();
        for (String id : List.of("r1", "r2", "r3")) {
            request(id, START, 100);
            caught(id, START + 10, "swallow", EXIT, id.hashCode(), "x.E", null);
        }
        CaughtExceptionRowDto row = single(resolve(change.apply(complete())));
        assertThat(row.unknown()).as(reason).isEqualTo(3);
        assertThat(row.unknownReason()).isEqualTo(reason);
        assertThat(row.finding()).isFalse();
    }

    private CaughtOutcomes.Context complete() {
        return new CaughtOutcomes.Context(
                NOW,
                true,
                true,
                true,
                true,
                false,
                null,
                null,
                source -> null,
                (from, to) -> false,
                null,
                request -> false,
                (from, to) -> null,
                true,
                Long.MIN_VALUE);
    }

    private static CaughtOutcomes.Context with(CaughtOutcomes.Context c, String what, long at) {
        return new CaughtOutcomes.Context(
                c.nowMillis(),
                !what.equals("undrained") && c.drained(),
                !what.equals("httpHidden") && c.httpVisible(),
                !what.equals("logHidden") && c.logVisible(),
                !what.equals("noHandoffs") && c.handoffsRecorded(),
                what.equals("unaccounted") || c.lossUnaccounted(),
                what.equals("cleared") ? Long.valueOf(at) : c.clearedAtMillis(),
                what.equals("horizon") ? Long.valueOf(at) : c.lossHorizonMillis(),
                what.equals("lostLog") ? source -> source == JournalSource.LOG ? at : null : c.lostMillis(),
                what.equals("agentLost") ? (from, to) -> true : c.agentLost(),
                what.equals("forgotten") ? Long.valueOf(at) : c.handoffsForgottenMillis(),
                what.equals("handoffRunning") ? request -> true : c.handoffRunning(),
                what.equals("logGap")
                        ? (from, to) -> "a logger bypasses BootUI"
                        : what.equals("logHidden") ? (from, to) -> "Log Tail is hidden" : c.logGap(),
                !what.equals("noReports") && c.reportsRecorded(),
                what.equals("marksSince") ? at : c.marksSinceMillis());
    }

    private CaughtExceptionsReport resolve(CaughtOutcomes.Context context) {
        return CaughtOutcomes.resolve(entries, context, List.of());
    }

    private static CaughtExceptionRowDto single(CaughtExceptionsReport report) {
        assertThat(report.rows()).hasSize(1);
        return report.rows().get(0);
    }

    private static Map<String, CaughtExceptionRowDto> byMethod(CaughtExceptionsReport report) {
        Map<String, CaughtExceptionRowDto> rows = new java.util.HashMap<>();
        report.rows().forEach(row -> rows.put(row.method(), row));
        return rows;
    }

    private void request(String id, long start, long durationMillis) {
        add(JournalSource.HTTP, start, id, "t1", http(200, false), durationMillis);
    }

    private static HttpPayload http(int status, boolean async) {
        return new HttpPayload("GET", "/api/caught", "/api/caught", null, status, null, null, async);
    }

    private void caught(String request, long time, String method, int flags, int identity, String type, String family) {
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                time,
                request,
                "t1",
                payload(CaughtExceptionPayload.CAUGHT, method, flags, type, family, identity));
    }

    private void thrown(String request, long time, String method, int flags, int identity) {
        add(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                time,
                request,
                null,
                payload(CaughtExceptionPayload.THROWN, method, flags, null, null, identity));
    }

    private static CaughtExceptionPayload payload(
            String kind, String method, int flags, String type, String family, int identity) {
        return new CaughtExceptionPayload(
                kind,
                "com.example.Shop",
                method + "()V",
                12,
                List.of("x/E"),
                flags,
                type,
                family,
                1,
                null,
                null,
                identity);
    }

    private void add(JournalSource source, long time, String request, String thread, RuntimeEventPayload payload) {
        add(source, time, request, thread, payload, -1);
    }

    private void add(
            JournalSource source,
            long time,
            String request,
            String thread,
            RuntimeEventPayload payload,
            long durationMillis) {
        add(source, time, request, thread, payload, durationMillis < 0 ? -1 : durationMillis * 1_000_000L, true);
    }

    private void add(
            JournalSource source,
            long time,
            String request,
            String thread,
            RuntimeEventPayload payload,
            long durationNanos,
            boolean nanos) {
        RuntimeEvent event =
                new RuntimeEvent(source, time, durationNanos, request, null, null, thread, null, false, payload);
        entries.add(new JournalEntry(++sequence, event, 100));
    }
}
