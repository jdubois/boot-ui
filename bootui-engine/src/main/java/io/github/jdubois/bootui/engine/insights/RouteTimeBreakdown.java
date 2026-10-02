package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
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
    public Set<JournalSource> optionalReads() {
        return Set.of(
                JournalSource.SQL, JournalSource.CONNECTION, JournalSource.REST_CLIENT, JournalSource.AUTHORIZATION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean firstRetainedIsFirst =
                snapshot.status().evictedByCount() + snapshot.status().evictedByBytes() == 0;
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
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
            eligible += warm.size();
            findings.add(finding(route.getKey(), warm, cold, unplaced, firstRetainedIsFirst, snapshot));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(
            String route,
            List<Breakdown> warm,
            ProjectedRequest cold,
            long unplaced,
            boolean coldKnown,
            InsightsSnapshot snapshot) {
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
                    + " unattributed span around the calls.");
        }
        if (snapshot.stack() == InsightsStack.QUARKUS) {
            limitations.add("Hibernate ORM statements on Quarkus are timed as preparations, so their SQL time is"
                    + " unknown and counts as handler work.");
        }
        String coldText =
                cold == null ? "" : " First request " + InsightText.millis(cold.durationNanos()) + " ms (cold).";
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
        Map<Phase, long[]> totals = new EnumMap<>(Phase.class);
        long total = 0;
        long overlap = 0;
        long[] durations = new long[warm.size()];
        for (int i = 0; i < warm.size(); i++) {
            Breakdown breakdown = warm.get(i);
            total += breakdown.duration();
            overlap += breakdown.overlap();
            durations[i] = breakdown.duration();
            for (Map.Entry<Phase, Long> phase : breakdown.phases().entrySet()) {
                long[] perPhase = totals.computeIfAbsent(phase.getKey(), p -> new long[warm.size() + 1]);
                perPhase[0] += phase.getValue();
                perPhase[i + 1] = phase.getValue();
            }
        }
        long median = median(durations);
        long sum = Math.max(1, total);
        List<Map.Entry<Phase, long[]>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort(Comparator.comparingLong((Map.Entry<Phase, long[]> e) -> e.getValue()[0])
                .reversed());
        List<String> top = new ArrayList<>();
        for (Map.Entry<Phase, long[]> phase : ranked) {
            if (top.size() == 3 || phase.getValue()[0] == 0) {
                break;
            }
            top.add(phase.getKey().label + " " + percent(phase.getValue()[0], sum));
        }
        boolean dominant = !ranked.isEmpty() && ranked.get(0).getValue()[0] * 2 >= total;
        String sentence = "`" + route + "`: warm median " + InsightText.millis(median) + " ms over "
                + InsightText.counted(warm.size(), "request") + "; " + String.join(", ", top) + "."
                + resources(warm) + coldText;
        List<List<String>> rows = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            long[] perPhase = totals.get(phase);
            if (perPhase == null || perPhase[0] == 0) {
                continue;
            }
            rows.add(List.of(
                    phase.label,
                    InsightText.millis(perPhase[0]),
                    percent(perPhase[0], sum),
                    InsightText.millis(median(Arrays.copyOfRange(perPhase, 1, perPhase.length)))));
        }
        if (overlap > 0) {
            rows.add(List.of("Overlapping calls, counted once above", InsightText.millis(overlap), "", ""));
        }
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
                warm.stream()
                        .sorted(Comparator.comparingLong(Breakdown::duration).reversed())
                        .limit(3)
                        .map(b -> b.request().requestId())
                        .toList(),
                List.of("Phase", "Total (ms)", "Share", "Median per request (ms)"),
                rows,
                limitations);
    }

    private static String check(Phase phase) {
        return switch (phase) {
            case SQL -> "Most of the time is SQL: compare its statements in SQL Trace, and see Repeated SELECTs.";
            case CONNECTION_WAIT ->
                "Most of the time is waiting for a connection: compare the pool size with Connections per request.";
            case REST_CLIENT -> "Most of the time is outgoing calls: check their latency in REST Client.";
            case RESPONSE ->
                "Most of the time is writing the response: check its size and any lazy loading during serialization.";
            case AUTHENTICATION -> "Most of the time is authentication: check how credentials are verified.";
            case AUTHORIZATION ->
                "Most of the time is authorization: check the rules and method-security expressions evaluated per"
                        + " request.";
            case FILTERS -> "Most of the time is in filters before the handler: check what they do per request.";
            case HANDLER, UNATTRIBUTED ->
                "Most of the time is in application code outside recorded calls: run Profile resources to see the"
                        + " route's hottest frames.";
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
                            if (covering == null || kind.ordinal() < covering.ordinal()) {
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
            if (timing.authenticationNanos() > 0 && phases.containsKey(Phase.FILTERS)) {
                long filters = phases.get(Phase.FILTERS);
                long authentication = Math.min(filters, timing.authenticationNanos());
                phases.put(Phase.FILTERS, filters - authentication);
                phases.merge(Phase.AUTHENTICATION, authentication, Long::sum);
            }
            if (timing.phased()) {
                carveAuthorization(request, phases);
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
            carve(phases, Phase.FILTERS, requestChecks);
            carve(phases, Phase.HANDLER, methodChecks);
        }

        private static void carve(Map<Phase, Long> phases, Phase from, long nanos) {
            Long available = phases.get(from);
            if (nanos <= 0 || available == null) {
                return;
            }
            long moved = Math.min(available, nanos);
            phases.put(from, available - moved);
            phases.merge(Phase.AUTHORIZATION, moved, Long::sum);
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
                default -> Phase.REST_CLIENT;
            };
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
