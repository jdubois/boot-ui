package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code route-time-breakdown} ({@code docs/PLAN-v2.md} §5.5): where a route's warm requests spend their time, as named
 * phases. Calls are placed by their monotonic times and unioned, so overlapping calls never add up to more than the
 * request, and the overlap is shown. Each route's first request in the run is reported apart as cold.
 *
 * <p>Time is never called the application's unless BootUI saw the handler run. On Spring MVC and Quarkus, which mark
 * when a handler begins, a request without that mark never reached a handler BootUI marks (an Actuator or {@code /q/}
 * endpoint, a request the security filters answered): it is left out of its route's phases, and a route made mostly of
 * such requests is reported insufficient with its recorded calls. On Spring WebFlux, which marks no handler or
 * response phase, a route whose requests named nothing at all, neither a recorded call nor authentication time, is
 * insufficient, since its breakdown would be one unattributed span.</p>
 */
public final class RouteTimeBreakdown implements Observation {

    public static final String KIND = "route-time-breakdown";

    static final int MIN_WARM_REQUESTS = 5;

    /** The median from which, with a dominant phase, a breakdown is worth reading first. */
    static final long PROMINENT_MEDIAN_NANOS = 20_000_000;

    /** Where a request's time went, in the order shown. */
    enum Phase {
        AUTHENTICATION("Authentication"),
        AUTHORIZATION("Authorization"),
        FILTERS("Other filters"),
        CONNECTION_WAIT("Connection wait"),
        SQL("SQL"),
        REST_CLIENT("REST client"),
        AI("AI calls"),
        MESSAGE_SENDS("Message sends"),
        HIBERNATE("Hibernate flushes"),
        HANDLER("Handler, other work"),
        RESPONSE("Response write"),
        UNATTRIBUTED("Unattributed");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Route time breakdown";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(
                JournalSource.SQL,
                JournalSource.CONNECTION,
                JournalSource.REST_CLIENT,
                JournalSource.AUTHORIZATION,
                JournalSource.AI,
                JournalSource.ORM,
                JournalSource.MESSAGING);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean firstRetainedIsFirst =
                snapshot.status().evictedByCount() + snapshot.status().evictedByBytes() == 0;
        boolean marksPhases = marksPhases(snapshot.stack());
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            ProjectedRequest cold = firstRetainedIsFirst ? requests.get(0) : null;
            List<Breakdown> warm = new ArrayList<>();
            long unplaced = 0;
            for (ProjectedRequest request : requests) {
                if (request == cold) {
                    continue;
                }
                Breakdown breakdown = Breakdown.of(request);
                if (breakdown == null) {
                    unplaced++;
                } else {
                    warm.add(breakdown);
                }
            }
            // On a stack that marks phases, a request with no handler mark never reached a handler BootUI marks: an
            // Actuator endpoint, a request the security filters answered, a framework route. Its time is not the
            // application's handler work, so it is never split as if it were.
            List<Breakdown> unmarked = marksPhases
                    ? warm.stream()
                            .filter(breakdown -> !breakdown.request().timing().phased())
                            .toList()
                    : List.of();
            List<String> limitations = limitations(snapshot, firstRetainedIsFirst, unplaced, warm);
            if (!warm.isEmpty() && unmarked.size() * 2 > warm.size()) {
                findings.add(unmarkedFinding(route.getKey(), warm, unmarked, cold, snapshot.stack(), limitations));
                continue;
            }
            List<Breakdown> split = warm;
            if (!unmarked.isEmpty()) {
                split = warm.stream()
                        .filter(breakdown -> breakdown.request().timing().phased())
                        .toList();
                limitations.add(leftOut(unmarked, snapshot.stack()));
            }
            eligible += split.size();
            findings.add(finding(route.getKey(), split, cold, snapshot.stack(), limitations));
        }
        return new Evaluation(eligible, findings);
    }

    /** Whether the stack marks when a request's handler begins: Spring MVC and Quarkus do, Spring WebFlux does not. */
    static boolean marksPhases(InsightsStack stack) {
        return stack == InsightsStack.SPRING_MVC || stack == InsightsStack.QUARKUS;
    }

    private static List<String> limitations(
            InsightsSnapshot snapshot, boolean coldKnown, long unplaced, List<Breakdown> warm) {
        List<String> limitations = new ArrayList<>();
        if (!coldKnown) {
            limitations.add("The journal evicted older events, so a route's first request may be gone and none is"
                    + " reported apart as cold.");
        }
        if (unplaced > 0) {
            limitations.add(InsightText.counted(unplaced, "request") + " had no monotonic start and are left out.");
        }
        if (warm.stream()
                .anyMatch(breakdown -> breakdown.request().resources() != null
                        && breakdown.request().resources().unmeasuredReason()
                                == ResourceUsage.Unmeasured.VIRTUAL_THREAD)) {
            limitations.add(
                    "Some requests ran on virtual threads, whose CPU time and allocation the JVM does not report"
                            + " per thread: Profile resources samples them with JFR.");
        }
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add("Spring WebFlux marks no phases, so filters, handler, and response write are one"
                    + " unattributed span around the calls, out of which only authentication time is named.");
        }
        if (snapshot.stack() == InsightsStack.QUARKUS) {
            boolean sessions = warm.stream()
                    .anyMatch(breakdown ->
                            !breakdown.request().children(JournalSource.ORM).isEmpty());
            limitations.add(
                    sessions
                            ? "Hibernate ORM statements on Quarkus are timed by Hibernate's sessions (the orm source),"
                                    + " not on the request's clock, so their SQL time is a total, not a span."
                            : "Hibernate ORM statements on Quarkus are timed as preparations, so their SQL time is"
                                    + " unknown and counts as handler work: record the orm source to measure it.");
        }
        long kafka = warm.stream()
                .filter(breakdown -> Breakdown.sendsAsynchronously(breakdown.request()))
                .count();
        if (kafka > 0) {
            limitations.add(InsightText.counted(kafka, "request") + " sent Kafka messages: a Kafka send is timed"
                    + " until the broker acknowledges it asynchronously, which the handler does not wait for, so its"
                    + " time is not counted as Message sends.");
        }
        long wallClockAi = warm.stream()
                .filter(breakdown -> Breakdown.placesAiByWallClock(breakdown.request()))
                .count();
        if (wallClockAi > 0) {
            limitations.add(InsightText.counted(wallClockAi, "request") + " made AI calls known only from GenAI spans,"
                    + " which are placed by their wall-clock start to the millisecond rather than on the request's"
                    + " clock, so their AI time beside the calls they made can be off by about a millisecond.");
        }
        return limitations;
    }

    private static String coldText(ProjectedRequest cold) {
        return cold == null ? "" : " First request " + InsightText.millis(cold.durationNanos()) + " ms (cold).";
    }

    private Finding finding(
            String route, List<Breakdown> warm, ProjectedRequest cold, InsightsStack stack, List<String> limitations) {
        String coldText = coldText(cold);
        if (warm.size() < MIN_WARM_REQUESTS) {
            return new Finding(
                    route,
                    route,
                    false,
                    "`" + route + "`: " + warm.size() + " of " + MIN_WARM_REQUESTS + " warm requests needed."
                            + coldText,
                    warm.size(),
                    warm.size(),
                    List.of("Exercise the route a few more times to read where its time goes."),
                    cold == null ? List.of() : List.of(cold.requestId()),
                    List.of(),
                    List.of(),
                    limitations);
        }
        Totals totals = Totals.of(warm);
        long median = median(totals.durations());
        String warmText = "`" + route + "`: warm median " + InsightText.millis(median) + " ms over "
                + InsightText.counted(warm.size(), "request");
        List<String> exemplars = slowest(warm);
        if (totals.onlyUnattributed()) {
            // Without phase marks and without a recorded call, the whole request is one unattributed span: the
            // breakdown would say nothing about where the time went.
            return new Finding(
                    route,
                    route,
                    false,
                    warmText + ", none of it in a recorded call; " + stackName(stack)
                            + " marks no phases, so where that time went is not known." + rejected(warm)
                            + resources(warm) + coldText,
                    warm.size(),
                    warm.size(),
                    List.of("Open the slowest exemplar request in Live Activity to see its timeline."),
                    exemplars,
                    COLUMNS,
                    totals.rows(),
                    limitations);
        }
        List<Map.Entry<Phase, long[]>> ranked = totals.ranked();
        List<String> top = new ArrayList<>();
        for (Map.Entry<Phase, long[]> phase : ranked) {
            if (top.size() == 3 || phase.getValue()[0] == 0) {
                break;
            }
            top.add(phase.getKey().label + " " + percent(phase.getValue()[0], totals.sum()));
        }
        boolean dominant = !ranked.isEmpty() && ranked.get(0).getValue()[0] * 2 >= totals.total();
        String sentence = warmText + "; " + String.join(", ", top) + "." + resources(warm) + coldText;
        List<String> checks = new ArrayList<>();
        if (!ranked.isEmpty()) {
            checks.add(check(ranked.get(0).getKey()));
        }
        if (median >= PROMINENT_MEDIAN_NANOS || dominant) {
            checks.add("Open the slowest exemplar request in Live Activity to see its timeline.");
        }
        return new Finding(
                route,
                route,
                true,
                sentence,
                warm.size(),
                warm.size(),
                checks,
                exemplars,
                COLUMNS,
                totals.rows(),
                limitations);
    }

    /**
     * A route whose warm requests mostly reached no handler BootUI marks: not split into phases, since none of its
     * time is known to be the handler's. Its recorded calls are still named.
     */
    private Finding unmarkedFinding(
            String route,
            List<Breakdown> warm,
            List<Breakdown> unmarked,
            ProjectedRequest cold,
            InsightsStack stack,
            List<String> limitations) {
        long[] durations = warm.stream().mapToLong(Breakdown::duration).toArray();
        long rejected = unmarked.stream().filter(b -> rejected(b.request())).count();
        String which = unmarked.size() == warm.size() ? "all of them" : unmarked.size() + " of them";
        StringBuilder sentence = new StringBuilder("`")
                .append(route)
                .append("`: warm median ")
                .append(InsightText.millis(median(durations)))
                .append(" ms over ")
                .append(InsightText.counted(warm.size(), "request"))
                .append("; ")
                .append(which);
        if (rejected == unmarked.size()) {
            sentence.append(" answered 401 or 403 before reaching a handler BootUI marks, as security filters do when"
                    + " they reject a request, so the time is not split into phases.");
        } else {
            sentence.append(" reached no handler BootUI marks (")
                    .append(unmarkedExamples(stack))
                    .append("), so the time is not split into phases.");
            if (rejected > 0) {
                sentence.append(' ')
                        .append(InsightText.counted(rejected, "request"))
                        .append(rejected == 1 ? " was" : " were")
                        .append(" answered 401 or 403.");
            }
        }
        Totals totals = Totals.of(unmarked);
        List<String> calls = new ArrayList<>();
        for (Map.Entry<Phase, long[]> phase : totals.ranked()) {
            // Authentication is an observed phase, not a call the request made, so it stays out of this sentence; the
            // evidence rows still name it.
            if (phase.getKey() != Phase.UNATTRIBUTED
                    && phase.getKey() != Phase.AUTHENTICATION
                    && phase.getValue()[0] > 0
                    && calls.size() < 3) {
                calls.add(phase.getKey().label + " " + percent(phase.getValue()[0], totals.sum()));
            }
        }
        if (!calls.isEmpty()) {
            sentence.append(" Recorded calls: ")
                    .append(String.join(", ", calls))
                    .append('.');
        }
        sentence.append(coldText(cold));
        return new Finding(
                route,
                route,
                false,
                sentence.toString(),
                warm.size(),
                unmarked.size(),
                List.of(
                        rejected == unmarked.size()
                                ? "Send the route requests it accepts to read where its handler's time goes."
                                : "Open the slowest exemplar request in Live Activity to see its timeline and"
                                        + " recorded calls."),
                slowest(unmarked),
                COLUMNS,
                totals.rows(),
                limitations);
    }

    /** Why requests left out of a route's split reached no marked handler. */
    private static String leftOut(List<Breakdown> unmarked, InsightsStack stack) {
        long rejected = unmarked.stream().filter(b -> rejected(b.request())).count();
        String why = rejected == unmarked.size()
                ? "were answered 401 or 403 before reaching a handler BootUI marks, as security filters do when they"
                        + " reject a request"
                : "reached no handler BootUI marks (" + unmarkedExamples(stack) + ")";
        return InsightText.counted(unmarked.size(), "warm request") + " " + why + ", so "
                + (unmarked.size() == 1 ? "it is" : "they are") + " left out of the phases.";
    }

    private static String unmarkedExamples(InsightsStack stack) {
        return stack == InsightsStack.QUARKUS
                ? "a framework endpoint such as /q/health, a Vert.x route or static resource, or a request an HTTP"
                        + " security policy answered"
                : "an Actuator endpoint, a request the security filters answered, or a servlet outside Spring MVC";
    }

    private static boolean rejected(ProjectedRequest request) {
        return request.status() == 401 || request.status() == 403;
    }

    /** Says so when every request was rejected by security, which no phase can show on a stack without marks. */
    private static String rejected(List<Breakdown> warm) {
        return warm.stream().allMatch(b -> rejected(b.request()))
                ? " Every one was answered 401 or 403, as security filters do when they reject a request."
                : "";
    }

    private static String stackName(InsightsStack stack) {
        return stack == null ? "This stack" : stack.label();
    }

    private static List<String> slowest(List<Breakdown> breakdowns) {
        return breakdowns.stream()
                .sorted(Comparator.comparingLong(Breakdown::duration).reversed())
                .limit(3)
                .map(b -> b.request().requestId())
                .toList();
    }

    private static final List<String> COLUMNS = List.of("Phase", "Total (ms)", "Share", "Median per request (ms)");

    /** Every phase's time over a route's requests: in total, and per request for its median. */
    private record Totals(Map<Phase, long[]> phases, long total, long overlap, long[] durations) {

        static Totals of(List<Breakdown> breakdowns) {
            Map<Phase, long[]> totals = new EnumMap<>(Phase.class);
            long total = 0;
            long overlap = 0;
            long[] durations = new long[breakdowns.size()];
            for (int i = 0; i < breakdowns.size(); i++) {
                Breakdown breakdown = breakdowns.get(i);
                total += breakdown.duration();
                overlap += breakdown.overlap();
                durations[i] = breakdown.duration();
                for (Map.Entry<Phase, Long> phase : breakdown.phases().entrySet()) {
                    long[] perPhase = totals.computeIfAbsent(phase.getKey(), p -> new long[breakdowns.size() + 1]);
                    perPhase[0] += phase.getValue();
                    perPhase[i + 1] = phase.getValue();
                }
            }
            return new Totals(totals, total, overlap, durations);
        }

        long sum() {
            return Math.max(1, total);
        }

        List<Map.Entry<Phase, long[]>> ranked() {
            List<Map.Entry<Phase, long[]>> ranked = new ArrayList<>(phases.entrySet());
            ranked.sort(Comparator.comparingLong((Map.Entry<Phase, long[]> e) -> e.getValue()[0])
                    .reversed());
            return ranked;
        }

        /** Whether no time at all was placed in a named phase: no phase marks, and no recorded call took time. */
        boolean onlyUnattributed() {
            for (Map.Entry<Phase, long[]> phase : phases.entrySet()) {
                if (phase.getKey() != Phase.UNATTRIBUTED && phase.getValue()[0] > 0) {
                    return false;
                }
            }
            return true;
        }

        List<List<String>> rows() {
            List<List<String>> rows = new ArrayList<>();
            for (Phase phase : Phase.values()) {
                long[] perPhase = phases.get(phase);
                if (perPhase == null || perPhase[0] == 0) {
                    continue;
                }
                rows.add(List.of(
                        phase.label,
                        InsightText.millis(perPhase[0]),
                        percent(perPhase[0], sum()),
                        InsightText.millis(median(Arrays.copyOfRange(perPhase, 1, perPhase.length)))));
            }
            if (overlap > 0) {
                rows.add(List.of("Overlapping calls, counted once above", InsightText.millis(overlap), "", ""));
            }
            return rows;
        }
    }

    private static String check(Phase phase) {
        return switch (phase) {
            case SQL -> "Most of the time is SQL: compare its statements in SQL Trace, and see Repeated SELECTs.";
            case CONNECTION_WAIT ->
                "Most of the time is waiting for a connection: compare the pool size with Connections per request.";
            case REST_CLIENT -> "Most of the time is outgoing calls: check their latency in REST Client.";
            case AI -> "Most of the time is AI calls: check their tokens and model in AI usage by route.";
            case HIBERNATE ->
                "Most of the time is Hibernate flushing and dirty-checking: see Hibernate auto-flushes and Large"
                        + " persistence contexts.";
            case MESSAGE_SENDS ->
                "Most of the time is sending messages synchronously: check whether the send must wait for the broker.";
            case RESPONSE ->
                "Most of the time is writing the response: check its size and any lazy loading during serialization.";
            case AUTHENTICATION -> "Most of the time is authentication: check how credentials are verified.";
            case AUTHORIZATION ->
                "Most of the time is authorization: check the rules and method-security expressions evaluated per"
                        + " request.";
            case FILTERS -> "Most of the time is in filters before the handler: check what they do per request.";
            case HANDLER ->
                "Most of the time is in the handler outside recorded calls: run Profile resources to see the"
                        + " route's hottest frames.";
            case UNATTRIBUTED ->
                "Most of the time is outside recorded calls, in filters, handler, or response write, which this stack"
                        + " does not tell apart: open the slowest exemplar request in Live Activity to see its"
                        + " timeline.";
        };
    }

    private static String resources(List<Breakdown> warm) {
        List<Long> cpu = new ArrayList<>();
        List<Long> allocated = new ArrayList<>();
        for (Breakdown breakdown : warm) {
            ResourceUsage usage = breakdown.request().resources();
            if (usage != null && usage.availability() == ResourceUsage.Availability.AVAILABLE) {
                cpu.add(usage.cpuNanos());
                allocated.add(usage.allocatedBytes());
            }
        }
        if (cpu.size() < MIN_WARM_REQUESTS) {
            return "";
        }
        return " Median CPU "
                + InsightText.millis(
                        median(cpu.stream().mapToLong(Long::longValue).toArray()))
                + " ms and "
                + bytes(median(allocated.stream().mapToLong(Long::longValue).toArray()))
                + " allocated per request.";
    }

    private static String bytes(long bytes) {
        if (bytes >= 1_048_576) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1_024.0);
    }

    private static String percent(long part, long whole) {
        return Math.round(part * 100.0 / whole) + " %";
    }

    static long median(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
    }

    /** One request's time, split into phases that add up to its duration. */
    record Breakdown(ProjectedRequest request, long duration, Map<Phase, Long> phases, long overlap) {

        /** The request's breakdown, or {@code null} when it has no monotonic start. */
        static Breakdown of(ProjectedRequest request) {
            RequestTiming timing = request.timing();
            if (timing == null || timing.startNanos() < 0) {
                return null;
            }
            long duration = request.durationNanos();
            List<long[]> calls = new ArrayList<>();
            List<Phase> kinds = new ArrayList<>();
            for (RuntimeEvent child : request.children()) {
                long[] interval = interval(child, timing.startNanos(), duration);
                if (interval != null) {
                    calls.add(interval);
                    kinds.add(kind(child));
                }
            }
            TreeSet<Long> bounds = new TreeSet<>();
            bounds.add(0L);
            bounds.add(duration);
            for (long[] call : calls) {
                bounds.add(call[0]);
                bounds.add(call[1]);
            }
            if (timing.phased()) {
                bounds.add(Math.min(duration, timing.handlerOffsetNanos()));
                if (timing.responseOffsetNanos() >= 0) {
                    bounds.add(Math.min(duration, timing.responseOffsetNanos()));
                }
            }
            Map<Phase, Long> phases = new EnumMap<>(Phase.class);
            long union = 0;
            Long previous = null;
            for (long bound : bounds) {
                if (previous != null && bound > previous) {
                    long from = previous;
                    long length = bound - from;
                    Phase covering = null;
                    for (int i = 0; i < calls.size(); i++) {
                        long[] call = calls.get(i);
                        if (call[0] <= from && call[1] >= bound) {
                            Phase kind = kinds.get(i);
                            if (covering == null || precedence(kind) < precedence(covering)) {
                                covering = kind;
                            }
                        }
                    }
                    if (covering != null) {
                        union += length;
                    } else {
                        covering = phaseAt(timing, from);
                    }
                    phases.merge(covering, length, Long::sum);
                }
                previous = bound;
            }
            if (timing.authenticationNanos() > 0) {
                // Authentication runs in the filters where the adapter marks them, and inside the request's single
                // unattributed span where it marks none, as on WebFlux.
                carve(
                        phases,
                        phases.containsKey(Phase.FILTERS) ? Phase.FILTERS : Phase.UNATTRIBUTED,
                        Phase.AUTHENTICATION,
                        timing.authenticationNanos());
            }
            if (timing.phased()) {
                carveAuthorization(request, phases);
                carveHandlerCalls(request, duration, calls, phases);
            }
            long raw = 0;
            for (long[] call : calls) {
                raw += call[1] - call[0];
            }
            return new Breakdown(request, duration, phases, Math.max(0, raw - union));
        }

        /**
         * Moves the request's authorization time out of the phase it ran in: a request's decision runs in the filters
         * before the handler, a method's in the handler. Decisions are not placed on the time axis, so only their total
         * moves, at most what the phase holds.
         */
        private static void carveAuthorization(ProjectedRequest request, Map<Phase, Long> phases) {
            long requestChecks = 0;
            long methodChecks = 0;
            for (RuntimeEvent child : request.children()) {
                if (child.payload() instanceof AuthorizationPayload decision && child.durationNanos() > 0) {
                    if (decision.request()) {
                        requestChecks += child.durationNanos();
                    } else {
                        methodChecks += child.durationNanos();
                    }
                }
            }
            carve(phases, Phase.FILTERS, Phase.AUTHORIZATION, requestChecks);
            carve(phases, Phase.HANDLER, Phase.AUTHORIZATION, methodChecks);
        }

        /**
         * The brokers whose recorded send blocks the caller until the broker took the message: a
         * {@code RabbitTemplate} send and a {@code JmsTemplate} send. A Kafka send is timed until the broker's
         * asynchronous acknowledgement, which a handler does not wait for unless it blocks on the returned future, so
         * it is never taken out of the handler's time.
         */
        static final Set<String> SYNCHRONOUS_SEND_BROKERS = Set.of("rabbitmq", "jms");

        /** Whether {@code request} sent a Kafka message, whose recorded time runs to an asynchronous acknowledgement. */
        static boolean sendsAsynchronously(ProjectedRequest request) {
            for (RuntimeEvent child : request.children(JournalSource.MESSAGING)) {
                if (child.payload() instanceof MessagingPayload message
                        && message.sent()
                        && "kafka".equalsIgnoreCase(message.broker())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean synchronousSend(MessagingPayload message) {
            return message.sent()
                    && message.broker() != null
                    && SYNCHRONOUS_SEND_BROKERS.contains(message.broker().toLowerCase(Locale.ROOT));
        }

        /**
         * Names the AI calls and synchronous message sends the handler made (M3-8), and Hibernate's own flush time
         * (M4-9), moving their time out of its other work. None is placed on the request's monotonic clock, so only
         * their totals move, at most what the handler holds, as with authorization.
         *
         * <p>An AI call the framework reported with its monotonic completion is placed by the sweep instead, over the
         * REST client call that carried it. One known only from a GenAI span is not: its window is placed from its
         * wall-clock start relative to the request's, to the millisecond, and only its model and embedding time inside
         * the handler that no placed call covers moves, since its HTTP call, or a placed call nested in it, is counted
         * already. Calls the request made outside that window stay their own. Statement time measured only in total,
         * without a place, is deducted in full, so it under-counts rather than over-counts. A tool or retrieval
         * operation runs application code whose SQL and calls are placed already, so it never moves.</p>
         */
        private static void carveHandlerCalls(
                ProjectedRequest request, long duration, List<long[]> placed, Map<Phase, Long> phases) {
            RequestTiming timing = request.timing();
            long handlerFrom = Math.min(duration, timing.handlerOffsetNanos());
            long handlerTo =
                    timing.responseOffsetNanos() >= 0 ? Math.min(duration, timing.responseOffsetNanos()) : duration;
            List<long[]> unplacedAi = new ArrayList<>();
            long sends = 0;
            long hibernate = 0;
            long ormStatements = 0;
            boolean measuredSql = false;
            for (RuntimeEvent child : request.children()) {
                if (child.durationNanos() <= 0) {
                    continue;
                }
                if (child.payload() instanceof AiPayload call) {
                    if (placedByWallClock(call)) {
                        // Only the part inside the handler moves out of it.
                        long from = (child.epochMillis() - request.startMillis()) * 1_000_000L;
                        long to = Math.min(handlerTo, from + child.durationNanos());
                        from = Math.max(handlerFrom, from);
                        if (to > from) {
                            unplacedAi.add(new long[] {from, to});
                        }
                    }
                } else if (child.payload() instanceof MessagingPayload message && synchronousSend(message)) {
                    sends += child.durationNanos();
                } else if (child.payload() instanceof OrmPayload orm) {
                    hibernate += orm.hibernateNanos();
                    ormStatements += Math.max(0, orm.statementNanos());
                } else if (child.source() == JournalSource.SQL) {
                    measuredSql = true;
                }
            }
            // Where SQL events carry no duration, as Quarkus's statement inspector records them, the ORM session's
            // measured statement time names the SQL phase instead (M4-9).
            long unplacedSql = 0;
            if (!measuredSql) {
                unplacedSql = carve(phases, Phase.HANDLER, Phase.SQL, ormStatements);
            }
            carve(phases, Phase.HANDLER, Phase.HIBERNATE, hibernate);
            carve(phases, Phase.HANDLER, Phase.AI, uncovered(unplacedAi, placed) - unplacedSql);
            carve(phases, Phase.HANDLER, Phase.MESSAGE_SENDS, sends);
        }

        /** Whether {@code call} is a model or embedding call with no monotonic time, placed by its wall-clock start. */
        static boolean placedByWallClock(AiPayload call) {
            return call.completedNanos() < 0
                    && (AiPayload.CHAT.equals(call.operation()) || AiPayload.EMBEDDINGS.equals(call.operation()));
        }

        /** Whether {@code request}'s handler made an AI call placed by its wall-clock start. */
        static boolean placesAiByWallClock(ProjectedRequest request) {
            if (request.timing() == null || !request.timing().phased()) {
                return false;
            }
            for (RuntimeEvent child : request.children(JournalSource.AI)) {
                if (child.durationNanos() > 0 && child.payload() instanceof AiPayload call && placedByWallClock(call)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The time {@code intervals} cover that no interval of {@code covered} does, counting overlaps once, as a
         * nested call inside its caller.
         */
        static long uncovered(List<long[]> intervals, List<long[]> covered) {
            List<long[]> merged = merge(covered);
            long remaining = 0;
            for (long[] interval : merge(intervals)) {
                long length = interval[1] - interval[0];
                for (long[] other : merged) {
                    length -= Math.max(0, Math.min(interval[1], other[1]) - Math.max(interval[0], other[0]));
                }
                remaining += length;
            }
            return remaining;
        }

        /** {@code intervals} sorted and merged where they overlap or touch. */
        private static List<long[]> merge(List<long[]> intervals) {
            List<long[]> sorted = new ArrayList<>(intervals);
            sorted.sort(Comparator.comparingLong(interval -> interval[0]));
            List<long[]> merged = new ArrayList<>();
            for (long[] interval : sorted) {
                long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && interval[0] <= last[1]) {
                    last[1] = Math.max(last[1], interval[1]);
                } else {
                    merged.add(new long[] {interval[0], interval[1]});
                }
            }
            return merged;
        }

        /** Moves up to {@code nanos} from {@code from} to {@code to}, and returns what it moved. */
        private static long carve(Map<Phase, Long> phases, Phase from, Phase to, long nanos) {
            Long available = phases.get(from);
            if (nanos <= 0 || available == null) {
                return 0;
            }
            long moved = Math.min(available, nanos);
            phases.put(from, available - moved);
            phases.merge(to, moved, Long::sum);
            return moved;
        }

        private static Phase phaseAt(RequestTiming timing, long offset) {
            if (!timing.phased()) {
                return Phase.UNATTRIBUTED;
            }
            if (offset < timing.handlerOffsetNanos()) {
                return Phase.FILTERS;
            }
            if (timing.responseOffsetNanos() >= 0 && offset >= timing.responseOffsetNanos()) {
                return Phase.RESPONSE;
            }
            return Phase.HANDLER;
        }

        private static Phase kind(RuntimeEvent child) {
            return switch (child.source()) {
                case CONNECTION -> Phase.CONNECTION_WAIT;
                case SQL -> Phase.SQL;
                case AI -> Phase.AI;
                default -> Phase.REST_CLIENT;
            };
        }

        /**
         * Which placed call names time that several cover, lowest first: an AI call names the REST client call that
         * carried it to the model, while SQL and connection waits inside it, such as a tool's, stay their own.
         */
        private static int precedence(Phase kind) {
            return kind == Phase.AI ? Phase.SQL.ordinal() * 2 + 1 : kind.ordinal() * 2;
        }

        /** {@code child}'s interval from the request's start, clipped to it, or {@code null} when it has none. */
        private static long[] interval(RuntimeEvent child, long startNanos, long duration) {
            long from;
            long to;
            if (child.payload() instanceof SqlPayload sql && sql.completedNanos() >= 0 && child.durationNanos() > 0) {
                to = sql.completedNanos() - startNanos;
                from = to - child.durationNanos();
            } else if (child.payload() instanceof RestClientPayload call
                    && call.completedNanos() >= 0
                    && child.durationNanos() > 0) {
                to = call.completedNanos() - startNanos;
                from = to - child.durationNanos();
            } else if (child.payload() instanceof AiPayload call
                    && call.completedNanos() >= 0
                    && child.durationNanos() > 0) {
                to = call.completedNanos() - startNanos;
                from = to - child.durationNanos();
            } else if (child.payload() instanceof ConnectionPayload connection
                    && connection.checkoutNanos() >= 0
                    && connection.waitNanos() > 0) {
                to = connection.checkoutNanos() - startNanos;
                from = to - connection.waitNanos();
            } else {
                return null;
            }
            from = Math.max(0, from);
            to = Math.min(duration, to);
            return to > from ? new long[] {from, to} : null;
        }
    }
}
