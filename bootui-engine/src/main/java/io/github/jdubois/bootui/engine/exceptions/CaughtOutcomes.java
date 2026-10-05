package io.github.jdubois.bootui.engine.exceptions;

import io.github.jdubois.bootui.core.dto.CaughtExceptionRowDto;
import io.github.jdubois.bootui.core.dto.CaughtExceptionsReport;
import io.github.jdubois.bootui.engine.journal.CaughtExceptionPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.ThrowableMarks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What became of each exception application code caught ({@code docs/PLAN-v2.md} M5-6): a pure, framework-neutral
 * reading of the journal's {@code agent.caught-exceptions}, {@code http}, {@code log}, and {@code exception} events.
 *
 * <p>Each occurrence gets one outcome, in this order: <b>rethrown</b> (the agent saw it leave a method, as is or as a
 * cause, or caught again); <b>reported</b> (its identity is on an exception the framework's error handling recorded);
 * <b>logged</b> (its identity is on a {@code WARN}+ log event, or a {@code WARN}+ was logged on the catching thread,
 * in its request or in none, before that thread caught anything else); then the handler's own shape:
 * <b>handed on</b>, <b>re-interrupted</b>, or <b>replaced</b>. A later attempt at the same handler, in the same request
 * and on the same thread, that was rethrown, reported, or logged makes the earlier ones <b>retried</b>.</p>
 *
 * <p>The rest is <b>not rethrown or logged</b> only when the evidence is complete (the honesty rule): its request
 * settled, nothing of it was lost, cleared, or ran after its response, the handler's rethrow was observable, the
 * exception is not a wrapper whose cause may have been logged, and BootUI saw every {@code WARN}+ log. Otherwise it is
 * <b>unknown</b>, with the first reason, or <b>pending</b> while its request has not settled. A caught exception is
 * never called swallowed.</p>
 */
public final class CaughtOutcomes {

    /** How long a request must have ended before its occurrences are judged. */
    public static final long SETTLE_MILLIS = 5_000L;

    /** How long the agent keeps an occurrence pending before it stops watching for its rethrow. */
    public static final long PENDING_MILLIS = 60_000L;

    /** Requests with an occurrence not rethrown or logged that make a row a finding. */
    public static final int FINDING_REQUESTS = 3;

    /** The most rows returned. */
    public static final int MAX_ROWS = 200;

    /** Site flags and shapes ({@code CaughtExceptions.FLAG_*}, {@code SHAPE_*}). */
    static final int FLAG_EXIT_HANDLER = 4;

    static final int SHAPE_DISCARDS = 32;
    static final int SHAPE_PRINTS_STACK_TRACE = 64;
    static final int SHAPE_REINTERRUPTS = 128;
    static final int SHAPE_PASSES_AS_VALUE = 256;
    static final int SHAPE_THROWS_NEW = 512;

    /** Exceptions that wrap the one that failed: its cause may have been logged where it was thrown. */
    static final Set<String> WRAPPERS = Set.of(
            "java.util.concurrent.ExecutionException",
            "java.util.concurrent.CompletionException",
            "java.lang.reflect.InvocationTargetException",
            "java.lang.reflect.UndeclaredThrowableException",
            "java.io.UncheckedIOException");

    static final String REASON_NO_REQUEST = "not caught under an HTTP request";
    static final String REASON_REQUEST_NOT_RETAINED = "its request's HTTP event is not retained";
    static final String REASON_CLEARED = "its request started before the last Clear recording";
    static final String REASON_LOST = "records of its request were lost";
    static final String REASON_LOSS_UNACCOUNTED = "the attached agent does not count its losses";
    static final String REASON_ASYNC = "its request's work outlived the HTTP event";
    static final String REASON_AFTER_RESPONSE = "its request's work ran after the response";
    static final String REASON_HANDOFFS = "the agent's executors sensor does not record handoffs";
    static final String REASON_EXPIRED = "the agent stopped watching it before its request ended";
    static final String REASON_NO_EXIT = "a rethrow from its method is not observable";
    static final String REASON_WRAPPER = "it wraps another exception, which may have been logged";
    static final String REASON_UNNAMED = "the agent could not name its thread or class";
    static final String REASON_COUNTED = "occurrences past the agent's per-thread bound were only counted";
    static final String REASON_BEFORE_MARKS = "its request started before BootUI matched logged exceptions";
    static final String REASON_REPORTS = "the runtime journal does not record reported exceptions";

    enum Outcome {
        RETHROWN,
        REPLACED,
        REPORTED,
        LOGGED,
        HANDED_ON,
        REINTERRUPTED,
        RETRIED,
        NOT_RETHROWN_OR_LOGGED,
        UNKNOWN,
        PENDING
    }

    /**
     * What the read knows beside the journal's events.
     *
     * @param nowMillis the read's time, by the wall clock
     * @param drained whether the read drained the agent and the journal first, so every record made before it is in
     * @param httpVisible whether HTTP Exchanges is visible: else rows carry no route or request id
     * @param logVisible whether Log Tail is visible and the {@code log} source records: else logs are not consulted
     * @param handoffsRecorded whether the agent's executors sensor records handoffs
     * @param lossUnaccounted whether the agent cannot count its losses
     * @param clearedAtMillis when the journal was last cleared, or {@code null}
     * @param lossHorizonMillis the journal's loss horizon, or {@code null}
     * @param lostMillis per source, the latest time an event of it was lost, or {@code null}
     * @param agentLost whether the agent's evidence may have lost records between two times
     * @param handoffsForgottenMillis when the running-handoff registry last forgot one, or {@code null}
     * @param handoffRunning whether a handoff of a request is still running
     * @param logGap why BootUI may not have seen every {@code WARN}+ log between two times, or {@code null}
     * @param reportsRecorded whether the journal records the exceptions the framework's error handling reported
     * @param marksSinceMillis since when logged and reported throwables carry identity marks, by the wall clock
     */
    public record Context(
            long nowMillis,
            boolean drained,
            boolean httpVisible,
            boolean logVisible,
            boolean handoffsRecorded,
            boolean lossUnaccounted,
            Long clearedAtMillis,
            Long lossHorizonMillis,
            Function<JournalSource, Long> lostMillis,
            LostBetween agentLost,
            Long handoffsForgottenMillis,
            Predicate<String> handoffRunning,
            LogGap logGap,
            boolean reportsRecorded,
            long marksSinceMillis) {}

    /** Whether evidence may have been lost between two times, by the wall clock. */
    @FunctionalInterface
    public interface LostBetween {
        boolean test(long from, long to);
    }

    /** Why BootUI may not have seen every {@code WARN}+ log between two times, or {@code null}. */
    @FunctionalInterface
    public interface LogGap {
        String between(long from, long to);
    }

    private CaughtOutcomes() {}

    /** One {@code caught} event. */
    static final class Occurrence {
        final RuntimeEvent event;
        final CaughtExceptionPayload payload;
        final String siteKey;
        Outcome outcome;
        String reason;

        Occurrence(RuntimeEvent event, CaughtExceptionPayload payload) {
            this.event = event;
            this.payload = payload;
            this.siteKey = siteKey(payload);
        }

        long time() {
            return event.epochMillis();
        }
    }

    /** A request's HTTP event. */
    record Request(long start, long end, String route, boolean async, int status) {}

    /** A logged or reported throwable's marks. */
    record Mark(long time, boolean log, ThrowableMarks marks) {}

    /** A {@code WARN}+ log event on a thread. */
    record ThreadLog(long time, String requestId) {}

    /** The report over {@code entries}, every retained event of the journal, under {@code context}. */
    public static CaughtExceptionsReport resolve(
            List<JournalEntry> entries, Context context, List<String> limitations) {
        Map<String, Request> requests = new HashMap<>();
        List<Occurrence> occurrences = new ArrayList<>();
        Set<String> thrown = new HashSet<>();
        Set<String> evictedRequests = new HashSet<>();
        Map<String, Long> latestOfRequest = new HashMap<>();
        Map<String, Long> earliestOfRequest = new HashMap<>();
        Map<String, Long> counted = new HashMap<>();
        Map<String, CaughtExceptionPayload> countedSites = new LinkedHashMap<>();
        List<Mark> marks = new ArrayList<>();
        Map<String, List<ThreadLog>> threadLogs = new HashMap<>();
        Map<String, List<Long>> threadCatches = new HashMap<>();
        Set<String> countedOfRequest = new HashSet<>();

        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            String requestId = event.requestId();
            if (event.source() == JournalSource.HTTP && event.payload() instanceof HttpPayload http) {
                if (requestId != null) {
                    // Its start is truncated to the millisecond and its duration rounded down: the end is rounded up,
                    // so work in its last millisecond is never taken for work after the response.
                    long end = event.epochMillis() + (Math.max(0L, event.durationNanos()) + 999_999L) / 1_000_000L + 1L;
                    requests.put(
                            requestId,
                            new Request(
                                    event.epochMillis(),
                                    end,
                                    http.routeTemplate(),
                                    http.asyncStarted(),
                                    http.status()));
                }
                continue;
            }
            if (event.payload() instanceof CaughtExceptionPayload caught) {
                switch (caught.kind()) {
                    case CaughtExceptionPayload.CAUGHT -> {
                        occurrences.add(new Occurrence(event, caught));
                        if (event.thread() != null) {
                            threadCatches
                                    .computeIfAbsent(threadKey(event.thread(), requestId), key -> new ArrayList<>())
                                    .add(event.epochMillis());
                        }
                        stamp(requestId, event.epochMillis(), latestOfRequest, earliestOfRequest);
                    }
                    case CaughtExceptionPayload.THROWN -> {
                        thrown.add(thrownKey(requestId, siteKey(caught), caught.identity()));
                        stamp(requestId, event.epochMillis(), latestOfRequest, earliestOfRequest);
                    }
                    case CaughtExceptionPayload.UNTRACKED -> {
                        // Flushed after a scope ends, so never taken for work after the response.
                        String key = rowKey(null, ownerKind(event), siteKey(caught), null);
                        counted.merge(key, caught.count(), Long::sum);
                        countedSites.putIfAbsent(key, caught);
                        if (requestId != null) {
                            countedOfRequest.add(requestId + "\u0000" + siteKey(caught));
                        }
                    }
                    case CaughtExceptionPayload.EVICTED -> {
                        if (requestId != null) {
                            evictedRequests.add(requestId);
                        }
                    }
                    default -> {}
                }
                continue;
            }
            if (event.payload() instanceof LogPayload log) {
                if (!context.logVisible()) {
                    continue;
                }
                stamp(requestId, event.epochMillis(), latestOfRequest, earliestOfRequest);
                if (!LogPayload.isRecorded(log.level())) {
                    continue;
                }
                if (log.marks() != null) {
                    marks.add(new Mark(event.epochMillis(), true, log.marks()));
                }
                if (event.thread() != null) {
                    threadLogs
                            .computeIfAbsent(event.thread(), thread -> new ArrayList<>())
                            .add(new ThreadLog(event.epochMillis(), requestId));
                }
                continue;
            }
            if (event.payload() instanceof ExceptionPayload exception) {
                stamp(requestId, event.epochMillis(), latestOfRequest, earliestOfRequest);
                if (exception.marks() != null && (!exception.logged() || context.logVisible())) {
                    marks.add(new Mark(event.epochMillis(), exception.logged(), exception.marks()));
                }
                continue;
            }
            if (requestId != null) {
                stamp(requestId, event.epochMillis(), latestOfRequest, earliestOfRequest);
            }
        }
        threadCatches.values().forEach(times -> times.sort(Long::compare));

        // Positive evidence first.
        for (Occurrence occurrence : occurrences) {
            String requestId = occurrence.event.requestId();
            Request request = requestId == null ? null : requests.get(requestId);
            long from = request != null
                    ? request.start()
                    : earliestOfRequest.getOrDefault(requestId, occurrence.time() - SETTLE_MILLIS);
            int flags = occurrence.payload.siteFlags();
            if (thrown.contains(thrownKey(requestId, occurrence.siteKey, occurrence.payload.identity()))) {
                occurrence.outcome = Outcome.RETHROWN;
            } else if (marked(marks, occurrence.payload.identity(), from, false)) {
                occurrence.outcome = Outcome.REPORTED;
            } else if (marked(marks, occurrence.payload.identity(), from, true)) {
                occurrence.outcome = Outcome.LOGGED;
            } else if (context.logVisible() && loggedOnThread(occurrence, request, threadLogs, threadCatches)) {
                occurrence.outcome = Outcome.LOGGED;
            } else if ((flags & SHAPE_PASSES_AS_VALUE) != 0) {
                occurrence.outcome = Outcome.HANDED_ON;
            } else if ((flags & SHAPE_REINTERRUPTS) != 0) {
                occurrence.outcome = Outcome.REINTERRUPTED;
            } else if ((flags & SHAPE_THROWS_NEW) != 0) {
                occurrence.outcome = Outcome.REPLACED;
            }
        }
        // Retried: an earlier attempt of a later one that was rethrown, reported, or logged.
        Map<String, Long> lastSettledAttempt = new HashMap<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.outcome == Outcome.RETHROWN
                    || occurrence.outcome == Outcome.REPORTED
                    || occurrence.outcome == Outcome.LOGGED) {
                lastSettledAttempt.merge(attemptKey(occurrence), occurrence.time(), Math::max);
            }
        }
        for (Occurrence occurrence : occurrences) {
            if (occurrence.outcome != null) {
                continue;
            }
            Long later = lastSettledAttempt.get(attemptKey(occurrence));
            if (later != null && later >= occurrence.time()) {
                occurrence.outcome = Outcome.RETRIED;
                continue;
            }
            judge(occurrence, requests, evictedRequests, latestOfRequest, countedOfRequest, context);
        }
        return report(occurrences, requests, counted, countedSites, context, limitations);
    }

    /** The completeness rule: not rethrown or logged only when the evidence is complete. */
    private static void judge(
            Occurrence occurrence,
            Map<String, Request> requests,
            Set<String> evictedRequests,
            Map<String, Long> latestOfRequest,
            Set<String> countedOfRequest,
            Context context) {
        String requestId = occurrence.event.requestId();
        if (requestId == null) {
            unknown(occurrence, REASON_NO_REQUEST);
            return;
        }
        Request request = requests.get(requestId);
        if (request == null) {
            Long horizon = context.lossHorizonMillis();
            if (context.nowMillis() - occurrence.time() < PENDING_MILLIS
                    && (horizon == null || horizon < occurrence.time())) {
                occurrence.outcome = Outcome.PENDING;
            } else {
                unknown(occurrence, REASON_REQUEST_NOT_RETAINED);
            }
            return;
        }
        if (context.nowMillis() - request.end() < SETTLE_MILLIS
                || !context.drained()
                || context.handoffRunning().test(requestId)) {
            occurrence.outcome = Outcome.PENDING;
            return;
        }
        if (context.clearedAtMillis() != null && request.start() <= context.clearedAtMillis()) {
            unknown(occurrence, REASON_CLEARED);
            return;
        }
        if (request.start() < context.marksSinceMillis()) {
            unknown(occurrence, REASON_BEFORE_MARKS);
            return;
        }
        if (context.lossUnaccounted()) {
            unknown(occurrence, REASON_LOSS_UNACCOUNTED);
            return;
        }
        if (lost(request, requestId, evictedRequests, context)) {
            unknown(occurrence, REASON_LOST);
            return;
        }
        if (request.async() || request.status() == 0) {
            unknown(occurrence, REASON_ASYNC);
            return;
        }
        Long latest = latestOfRequest.get(requestId);
        Long forgotten = context.handoffsForgottenMillis();
        if ((latest != null && latest > request.end()) || (forgotten != null && forgotten >= request.start())) {
            unknown(occurrence, REASON_AFTER_RESPONSE);
            return;
        }
        if (!context.handoffsRecorded()) {
            unknown(occurrence, REASON_HANDOFFS);
            return;
        }
        if (request.end() - occurrence.time() > PENDING_MILLIS - SETTLE_MILLIS) {
            unknown(occurrence, REASON_EXPIRED);
            return;
        }
        int flags = occurrence.payload.siteFlags();
        if ((flags & (FLAG_EXIT_HANDLER | SHAPE_DISCARDS)) == 0) {
            unknown(occurrence, REASON_NO_EXIT);
            return;
        }
        if (WRAPPERS.contains(occurrence.payload.exceptionClass())) {
            unknown(occurrence, REASON_WRAPPER);
            return;
        }
        if (occurrence.event.thread() == null || occurrence.payload.exceptionClass() == null) {
            unknown(occurrence, REASON_UNNAMED);
            return;
        }
        if (countedOfRequest.contains(requestId + "\u0000" + occurrence.siteKey)) {
            unknown(occurrence, REASON_COUNTED);
            return;
        }
        if (!context.reportsRecorded()) {
            unknown(occurrence, REASON_REPORTS);
            return;
        }
        String gap = context.logGap().between(request.start(), request.end() + SETTLE_MILLIS);
        if (gap != null) {
            unknown(occurrence, gap);
            return;
        }
        occurrence.outcome = Outcome.NOT_RETHROWN_OR_LOGGED;
    }

    private static boolean lost(Request request, String requestId, Set<String> evicted, Context context) {
        if (evicted.contains(requestId)) {
            return true;
        }
        Long horizon = context.lossHorizonMillis();
        if (horizon != null && horizon >= request.start()) {
            return true;
        }
        for (JournalSource source : List.of(
                JournalSource.HTTP,
                JournalSource.LOG,
                JournalSource.EXCEPTION,
                JournalSource.AGENT_CAUGHT_EXCEPTIONS)) {
            Long lost = context.lostMillis().apply(source);
            if (lost != null && lost >= request.start()) {
                return true;
            }
        }
        return context.agentLost().test(request.start(), request.end() + SETTLE_MILLIS);
    }

    private static void unknown(Occurrence occurrence, String reason) {
        occurrence.outcome = Outcome.UNKNOWN;
        occurrence.reason = reason;
    }

    /** Whether a logged ({@code log}) or reported mark of {@code identity} was taken at or after {@code from}. */
    private static boolean marked(List<Mark> marks, int identity, long from, boolean log) {
        for (Mark mark : marks) {
            if (mark.log() == log && mark.time() >= from && mark.marks().contains(identity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@code WARN}+ was logged on the catching thread, in its request or in none, at or after the catch and
     * before that thread's next catch; one in no request also before the request ended.
     */
    private static boolean loggedOnThread(
            Occurrence occurrence,
            Request request,
            Map<String, List<ThreadLog>> threadLogs,
            Map<String, List<Long>> threadCatches) {
        String thread = occurrence.event.thread();
        if (thread == null) {
            return false;
        }
        List<ThreadLog> logs = threadLogs.get(thread);
        if (logs == null) {
            return false;
        }
        String requestId = occurrence.event.requestId();
        // The thread's next catch in the same request ends the window: another request's catch on a shared event loop
        // never does, nor any on a thread without a name, as unnamed virtual threads share the empty one.
        long next = Long.MAX_VALUE;
        if (!thread.isEmpty()) {
            for (long time : threadCatches.getOrDefault(threadKey(thread, requestId), List.of())) {
                if (time > occurrence.time()) {
                    next = time;
                    break;
                }
            }
        }
        long unowned = request != null ? request.end() : occurrence.time() + SETTLE_MILLIS;
        for (ThreadLog log : logs) {
            if (log.time() < occurrence.time() || log.time() > next) {
                continue;
            }
            if (log.requestId() == null
                    ? log.time() <= unowned
                    : log.requestId().equals(requestId)) {
                return true;
            }
        }
        return false;
    }

    private static CaughtExceptionsReport report(
            List<Occurrence> occurrences,
            Map<String, Request> requests,
            Map<String, Long> counted,
            Map<String, CaughtExceptionPayload> countedSites,
            Context context,
            List<String> limitations) {
        Map<String, Row> rows = new LinkedHashMap<>();
        long settling = 0;
        for (Occurrence occurrence : occurrences) {
            String requestId = occurrence.event.requestId();
            Request request = requestId == null ? null : requests.get(requestId);
            String route = context.httpVisible() && request != null ? request.route() : null;
            String key =
                    rowKey(route, ownerKind(occurrence.event), occurrence.siteKey, occurrence.payload.exceptionClass());
            Row row =
                    rows.computeIfAbsent(key, k -> new Row(k, route, ownerKind(occurrence.event), occurrence.payload));
            row.add(occurrence, context.httpVisible());
            if (occurrence.outcome == Outcome.PENDING) {
                settling++;
            }
        }
        // Counted occurrences go to the rows of their site and owner kind, whatever their route and class.
        counted.forEach((key, count) -> {
            CaughtExceptionPayload site = countedSites.get(key);
            Row target = null;
            for (Row row : rows.values()) {
                if (row.siteKey.equals(siteKey(site)) && row.ownerKind.equals(key.split("\u0000", -1)[1])) {
                    target = row;
                    break;
                }
            }
            if (target == null) {
                target = rows.computeIfAbsent(key, k -> new Row(k, null, key.split("\u0000", -1)[1], site));
            }
            target.counted += count;
        });
        List<CaughtExceptionRowDto> dtos = new ArrayList<>();
        long total = 0;
        int findings = 0;
        for (Row row : rows.values()) {
            CaughtExceptionRowDto dto = row.dto();
            total += dto.occurrences();
            findings += dto.finding() ? 1 : 0;
            dtos.add(dto);
        }
        dtos.sort(Comparator.comparing((CaughtExceptionRowDto dto) -> !dto.finding())
                .thenComparing(Comparator.comparingLong(CaughtExceptionRowDto::occurrences)
                        .reversed())
                .thenComparing(CaughtExceptionRowDto::id));
        return new CaughtExceptionsReport(
                true,
                null,
                limitations,
                settling,
                total,
                findings,
                dtos.size() > MAX_ROWS ? dtos.subList(0, MAX_ROWS) : dtos);
    }

    /** One row being counted. */
    static final class Row {
        final String key;
        final String route;
        final String ownerKind;
        final String siteKey;
        final CaughtExceptionPayload site;
        final long[] outcomes = new long[Outcome.values().length];
        final Set<String> requests = new HashSet<>();
        final Set<String> notLoggedRequests = new HashSet<>();
        final Map<String, Integer> reasons = new LinkedHashMap<>();
        long occurrences;
        long counted;
        long firstSeen = Long.MAX_VALUE;
        long lastSeen = Long.MIN_VALUE;
        String exemplar;

        Row(String key, String route, String ownerKind, CaughtExceptionPayload site) {
            this.key = key;
            this.route = route;
            this.ownerKind = ownerKind;
            this.siteKey = siteKey(site);
            this.site = site;
        }

        void add(Occurrence occurrence, boolean httpVisible) {
            occurrences++;
            outcomes[occurrence.outcome.ordinal()]++;
            String requestId = occurrence.event.requestId();
            if (requestId != null) {
                requests.add(requestId);
                if (occurrence.outcome == Outcome.NOT_RETHROWN_OR_LOGGED) {
                    notLoggedRequests.add(requestId);
                }
                if (httpVisible && (exemplar == null || occurrence.outcome == Outcome.NOT_RETHROWN_OR_LOGGED)) {
                    exemplar = requestId;
                }
            }
            if (occurrence.reason != null) {
                reasons.merge(occurrence.reason, 1, Integer::sum);
            }
            firstSeen = Math.min(firstSeen, occurrence.time());
            lastSeen = Math.max(lastSeen, occurrence.time());
        }

        CaughtExceptionRowDto dto() {
            boolean finding = !notLoggedRequests.isEmpty()
                    && (notLoggedRequests.size() >= FINDING_REQUESTS || site.family() != null);
            String reason = reasons.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            return new CaughtExceptionRowDto(
                    id(key),
                    route,
                    ownerKind,
                    site.siteClass(),
                    methodName(site.siteMethod()),
                    site.line(),
                    site.declaredTypes().stream()
                            .map(type -> type.replace('/', '.'))
                            .toList(),
                    site.exceptionClass(),
                    site.family(),
                    occurrences,
                    requests.size(),
                    outcomes[Outcome.RETHROWN.ordinal()],
                    outcomes[Outcome.REPLACED.ordinal()],
                    outcomes[Outcome.REPORTED.ordinal()],
                    outcomes[Outcome.LOGGED.ordinal()],
                    outcomes[Outcome.HANDED_ON.ordinal()],
                    outcomes[Outcome.REINTERRUPTED.ordinal()],
                    outcomes[Outcome.RETRIED.ordinal()],
                    outcomes[Outcome.NOT_RETHROWN_OR_LOGGED.ordinal()],
                    outcomes[Outcome.UNKNOWN.ordinal()],
                    outcomes[Outcome.PENDING.ordinal()],
                    counted,
                    reason,
                    shapes(site.siteFlags()),
                    finding,
                    exemplar,
                    occurrences == 0 ? 0L : firstSeen,
                    occurrences == 0 ? 0L : lastSeen);
        }
    }

    static List<String> shapes(int flags) {
        Set<String> shapes = new LinkedHashSet<>();
        if ((flags & SHAPE_DISCARDS) != 0) {
            shapes.add("discards");
        }
        if ((flags & SHAPE_PRINTS_STACK_TRACE) != 0) {
            shapes.add("prints-stack-trace");
        }
        if ((flags & SHAPE_REINTERRUPTS) != 0) {
            shapes.add("reinterrupts");
        }
        if ((flags & SHAPE_PASSES_AS_VALUE) != 0) {
            shapes.add("passes-as-value");
        }
        if ((flags & SHAPE_THROWS_NEW) != 0) {
            shapes.add("throws-new");
        }
        return List.copyOf(shapes);
    }

    private static void stamp(String requestId, long time, Map<String, Long> latest, Map<String, Long> earliest) {
        if (requestId == null) {
            return;
        }
        latest.merge(requestId, time, Math::max);
        earliest.merge(requestId, time, Math::min);
    }

    static String ownerKind(RuntimeEvent event) {
        if (event.requestId() != null) {
            return "request";
        }
        String execution = event.executionId();
        if (execution == null) {
            return "none";
        }
        return execution.startsWith("task-") || execution.startsWith("async-") ? "task" : "execution";
    }

    static String siteKey(CaughtExceptionPayload site) {
        return site.siteClass() + "#" + site.siteMethod() + "#" + site.line() + "#"
                + String.join("|", site.declaredTypes());
    }

    private static String threadKey(String thread, String requestId) {
        return thread + "\u0000" + requestId;
    }

    private static String thrownKey(String requestId, String siteKey, int identity) {
        return requestId + "\u0000" + siteKey + "\u0000" + identity;
    }

    private static String attemptKey(Occurrence occurrence) {
        return occurrence.event.requestId() + "\u0000" + occurrence.event.thread() + "\u0000" + occurrence.siteKey;
    }

    private static String rowKey(String route, String ownerKind, String siteKey, String exceptionClass) {
        return route + "\u0000" + ownerKind + "\u0000" + siteKey + "\u0000" + exceptionClass;
    }

    private static String methodName(String method) {
        if (method == null) {
            return null;
        }
        int paren = method.indexOf('(');
        return paren < 0 ? method : method.substring(0, paren);
    }

    /** A short stable id of a row's grouping. */
    static String id(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                id.append(String.format("%02x", digest[i]));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
