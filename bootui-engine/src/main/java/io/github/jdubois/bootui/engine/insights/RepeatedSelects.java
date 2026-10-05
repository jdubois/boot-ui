package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.codepaths.CodePathStamps;
import io.github.jdubois.bootui.engine.codepaths.IssuingMethod;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * {@code repeated-selects} ({@code docs/PLAN-v2.md} §5.5): one literal-free SELECT fingerprint executed at least
 * {@value #MIN_REPEATS} times in a request after a different statement ran, the shape of a suspected N+1. Reported per
 * route and fingerprint once {@value #MIN_REQUESTS} requests show it; fewer are reported as insufficient.
 *
 * <p>The default agent list omits a finding only when it is insufficient, every duration is known, at least one is
 * measured, the sum across affected requests is under {@value #DEFAULT_LIST_FLOOR_NANOS} nanoseconds, and no affected
 * request repeated the statement {@value #HIGH_REPEAT_KEEP} or more times. A sufficient finding stays however cheap, so
 * a local-database N+1 is not hidden. Unmeasured time, including Quarkus ORM preparations recorded as {@code 0}, is not
 * treated as cheap.
 *
 * <p>With the BootUI agent's {@code code-paths} sensor ({@code docs/PLAN-v2.md} §5.14, M5-4c), statements carry the stamp
 * of the instrumented method that was innermost on their thread when they ran: the finding names that method, the one
 * that issued the repeats, such as a service method looping over a repository, beside the stack walk's call site. When
 * that method is a repository or DAO method of the application's own, which the sensor instruments as a bean, as a
 * Panache repository, a {@code @Repository} class, or a Spring Data custom implementation, the finding names the first
 * method above it in the route's tree that is not one, and the repository method it went through. Without stamps, its
 * text is unchanged.</p>
 */
public final class RepeatedSelects implements Observation {

    public static final String KIND = "repeated-selects";
    static final int MIN_REPEATS = 5;
    static final int MIN_REQUESTS = 3;

    /**
     * Executions in one affected request at or above which a cheap insufficient finding stays in the default list. A
     * single request that already repeated this often is the N+1 shape, not a sample too small to show.
     */
    static final int HIGH_REPEAT_KEEP = 10;

    /** Summed measured repeat time below which a cheap insufficient finding may leave the default list. Exactly this stays. */
    static final long DEFAULT_LIST_FLOOR_NANOS = 50_000_000L;

    /**
     * Stable limitation the agent view matches, attached only when every omit condition holds. Decided from the exact
     * nanoseconds rather than the displayed total.
     */
    static final String UNDER_DEFAULT_FLOOR =
            "Repeated SELECTs under 50 ms of summed measured time, seen in fewer than "
                    + MIN_REQUESTS + " requests and fewer than " + HIGH_REPEAT_KEEP
                    + " times in any one, are left out of the default agent list; ask for them with the query repeated-selects.";

    static final String UNMEASURED_REPEAT_TIME = "Total repeat time is unmeasured: every execution was recorded as 0.";

    static final String UNKNOWN_REPEAT_TIME = "Total repeat time is unknown.";

    static final String RESULT_SIZE_UNRECORDED =
            "Whether the repeat count tracks a parent result size is not" + " recorded: statements carry no row count.";

    private volatile IntFunction<String> methodKeys;
    private volatile BiFunction<String, Integer, IssuingMethod> issuingMethods;

    /**
     * Installs how a code-paths stamp's method id is named, {@code class#name+descriptor}, such as
     * {@code CodePathsService::methodKey}; without it, the finding names no issuing method.
     */
    public void setMethodKeys(IntFunction<String> methodKeys) {
        this.methodKeys = methodKeys;
    }

    /**
     * Installs how the method that issued a route's statements is found from a code-paths stamp's method id, such as
     * {@code CodePathsService::issuingMethod}, which looks past a repository method to the method that called it; it
     * takes precedence over {@link #setMethodKeys}.
     */
    public void setIssuingMethods(BiFunction<String, Integer, IssuingMethod> issuingMethods) {
        this.issuingMethods = issuingMethods;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Repeated SELECTs";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.SQL);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.TRANSACTION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean transactionsPlaceable =
                snapshot.available(JournalSource.TRANSACTION) && snapshot.dropped(JournalSource.TRANSACTION) == 0;
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, List<Repeat>> byFingerprint = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                for (Repeat repeat : repeats(request, transactionsPlaceable)) {
                    byFingerprint
                            .computeIfAbsent(repeat.fingerprint(), f -> new ArrayList<>())
                            .add(repeat);
                }
            }
            byFingerprint.forEach((fingerprint, repeats) -> findings.add(finding(
                    route.getKey(), fingerprint, repeats, route.getValue().size(), snapshot)));
        }
        return new Evaluation(snapshot.requests().size(), findings);
    }

    /** The SELECTs one request repeated after a different statement, one per fingerprint. */
    static List<Repeat> repeats(ProjectedRequest request, boolean transactionsPlaceable) {
        TransactionWindows windows = transactionsPlaceable ? new TransactionWindows(request) : null;
        Map<String, Repeat> counts = new LinkedHashMap<>();
        String firstFingerprint = null;
        for (RuntimeEvent event : request.children(JournalSource.SQL)) {
            if (!(event.payload() instanceof SqlPayload sql)) {
                continue;
            }
            String fingerprint = SqlShapes.fingerprint(sql.sql());
            if (firstFingerprint == null) {
                firstFingerprint = fingerprint;
            }
            if (!isSelect(fingerprint) || fingerprint.equals(firstFingerprint)) {
                // A repeat counts only after a different statement ran first, as a parent query would.
                continue;
            }
            Repeat current = counts.get(fingerprint);
            counts.put(
                    fingerprint,
                    current == null
                            ? Repeat.first(request, fingerprint, event, sql, windows)
                            : current.plus(event, sql, windows));
        }
        return counts.values().stream()
                .filter(repeat -> repeat.executions() >= MIN_REPEATS)
                .toList();
    }

    private Finding finding(
            String route, String fingerprint, List<Repeat> repeats, long eligible, InsightsSnapshot snapshot) {
        repeats.sort(Comparator.comparingInt(Repeat::executions).reversed());
        int most = repeats.get(0).executions();
        String statement = repeats.get(0).statement();
        boolean sufficient = repeats.size() >= MIN_REQUESTS;
        IntFunction<String> keys = methodKeys;
        BiFunction<String, Integer, IssuingMethod> found = issuingMethods;
        Map<Integer, IssuingMethod> methods = new HashMap<>();
        java.util.function.Function<Integer, IssuingMethod> names = found == null && keys == null
                ? null
                : id -> methods.computeIfAbsent(id, ignored -> {
                    try {
                        return found != null ? found.apply(route, id) : IssuingMethod.of(keys.apply(id));
                    } catch (RuntimeException ex) {
                        return null;
                    }
                });
        Map<Integer, Integer> issuers = new LinkedHashMap<>();
        if (names != null) {
            for (Repeat repeat : repeats) {
                repeat.issuers().forEach((id, count) -> issuers.merge(id, count, Integer::sum));
            }
        }
        Issuer issuer = issuers.isEmpty() ? null : issuer(issuers, names);
        boolean named = issuer != null;
        String sentence = sufficient
                ? "`" + route + "` ran `" + statement + "` " + MIN_REPEATS
                        + " or more times after"
                        + " another statement in " + repeats.size() + " of "
                        + InsightText.counted(eligible, InsightText.unit(route)) + ", up to " + most + " times in one."
                : "`" + route + "`: " + repeats.size() + " of " + MIN_REQUESTS + " " + InsightText.unit(route)
                        + "s needed to report `" + statement + "` repeated " + MIN_REPEATS
                        + " or more times.";
        if (named) {
            sentence += " Issued by " + issuer.markdown() + ".";
        }
        List<List<String>> rows = new ArrayList<>();
        long totalNanos = 0;
        boolean unknownTime = false;
        boolean measured = false;
        for (Repeat repeat : repeats) {
            List<String> row = new ArrayList<>(List.of(
                    repeat.request().requestId(),
                    String.valueOf(repeat.executions()),
                    repeat.unknownTime() ? "unknown" : InsightText.millis(repeat.nanos()),
                    repeat.callSite() == null ? "" : repeat.callSite(),
                    repeat.phase(),
                    repeat.inTransaction()));
            if (named) {
                Issuer own = repeat.issuers().isEmpty() ? null : issuer(repeat.issuers(), names);
                row.add(own == null ? "unknown" : own.plain());
            }
            rows.add(List.copyOf(row));
            unknownTime |= repeat.unknownTime();
            measured |= repeat.measured();
            totalNanos += repeat.nanos();
        }
        List<String> limitations = new ArrayList<>();
        limitations.add("Counts statements the request ran; a statement Hibernate batches counts once.");
        limitations.add(RESULT_SIZE_UNRECORDED);
        if (unknownTime) {
            limitations.add(UNKNOWN_REPEAT_TIME);
        } else if (!measured) {
            // Quarkus ORM capture records preparations as 0: StatementInspector has no execution-end hook, and
            // SqlTraceRecorder clamps a negative duration to 0, so the journal cannot tell that from a timed 0.
            limitations.add(UNMEASURED_REPEAT_TIME);
        } else {
            limitations.add(
                    "Total repeat time is " + InsightText.millis(totalNanos) + " ms, summed across affected requests.");
            if (!sufficient && totalNanos < DEFAULT_LIST_FLOOR_NANOS && most < HIGH_REPEAT_KEEP) {
                limitations.add(UNDER_DEFAULT_FLOOR);
            }
        }
        if (snapshot.available(JournalSource.TRANSACTION) && snapshot.dropped(JournalSource.TRANSACTION) > 0) {
            limitations.add("Transaction events were dropped, so whether repeats ran inside one is unknown.");
        }
        if (named) {
            boolean through = false;
            boolean repositoryOnly = false;
            for (int id : issuers.keySet()) {
                IssuingMethod method = names.apply(id);
                if (method != null) {
                    through |= method.repositoryKey() != null;
                    repositoryOnly |= method.repository();
                }
            }
            limitations.add("The issuing method is the instrumented application method that was innermost on the"
                    + " statement's thread when it ran, from the BootUI agent's code paths. A repository Spring Data"
                    + " generates is not instrumented, so for one it is the method that called the repository"
                    + (through
                            ? "; a repository or DAO class of the application's own is, so the finding names the first"
                                    + " method above it that is not one, and the repository method it went through."
                            : "."));
            if (repositoryOnly) {
                limitations.add("A repository or DAO method of the application's own issued statements, and no method"
                        + " above it is known from the route's warm requests: look at what calls it.");
            }
        }
        List<String> checks = new ArrayList<>();
        if (named) {
            checks.add("Look at `" + issuer.label()
                    + "`, which issued the repeats: check whether it loops over the rows the"
                    + " first statement returned and loads each one's children.");
        }
        checks.add("Open an exemplar request in Live Activity and check whether each repeat loads the children"
                + " of one row the first statement returned.");
        checks.add("If it does, load them with one statement, such as a join or an IN list, in SQL Trace's"
                + " call site.");
        List<String> columns =
                new ArrayList<>(List.of("Request", "Executions", "Time (ms)", "Call site", "Phase", "In transaction"));
        if (named) {
            columns.add("Issuing method");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                repeats.size(),
                List.copyOf(checks),
                repeats.stream()
                        .limit(3)
                        .map(repeat -> repeat.request().requestId())
                        .toList(),
                List.copyOf(columns),
                rows,
                limitations);
    }

    /**
     * The issuing method of {@code issuers}' executions, {@code SimpleClass.method}: the one that issued them all, else
     * the one that issued most with how many others; {@code null} when no stamp names a known method.
     */
    private static Issuer issuer(
            Map<Integer, Integer> issuers, java.util.function.Function<Integer, IssuingMethod> names) {
        Map<String, Integer> labels = new LinkedHashMap<>();
        Map<String, String> through = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : issuers.entrySet()) {
            IssuingMethod method = names.apply(entry.getKey());
            if (method == null || method.key() == null) {
                // An unknown method names nothing.
                continue;
            }
            String label = CodePathStamps.label(method.key());
            labels.merge(label, entry.getValue(), Integer::sum);
            if (method.repositoryKey() != null) {
                through.putIfAbsent(label, CodePathStamps.label(method.repositoryKey()));
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        Map.Entry<String, Integer> most = labels.entrySet().stream()
                .min(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow();
        return new Issuer(most.getKey(), through.get(most.getKey()), labels.size() - 1);
    }

    /**
     * The method that issued the most of a statement's repeats, the repository method it went through, if any, and how
     * many other methods issued the rest.
     */
    private record Issuer(String label, String through, int others) {

        /** As the sentence names it, in code spans. */
        String markdown() {
            return "`" + label + "`" + (through == null ? "" : ", through `" + through + "`") + more();
        }

        /** As a table cell names it. */
        String plain() {
            return label + (through == null ? "" : " (through " + through + ")") + more();
        }

        private String more() {
            return others == 0 ? "" : " and " + others + (others == 1 ? " other method" : " other methods");
        }
    }

    private static boolean isSelect(String fingerprint) {
        return fingerprint != null && fingerprint.stripLeading().regionMatches(true, 0, "select", 0, 6);
    }

    private static String phaseLabel(RequestPhase phase) {
        if (phase == null) {
            return "unknown";
        }
        return switch (phase) {
            case HANDLER -> "handler";
            case RESPONSE -> "response write";
            case FILTERS -> "filters";
        };
    }

    /**
     * {@code yes} inside a recorded transaction, {@code no} when transactions were recorded and the statement is
     * placeable outside every window, otherwise {@code unknown}. Absence of the transaction source, a dropped
     * transaction event, or a statement that cannot be placed is never reported as {@code no}.
     */
    private static String transactionLabel(RuntimeEvent event, TransactionWindows windows) {
        if (windows == null || !windows.canPlace(event)) {
            return "unknown";
        }
        return windows.innermost(event) == null ? "no" : "yes";
    }

    /** Marks a call site counted from a statement run after the handler returned. */
    private static final String AFTER_HANDLER = "\u0000after:";

    private static String merge(String current, String next) {
        return current.equals(next) ? current : "mixed";
    }

    /**
     * @param statement the statement as a sentence quotes it, which {@code fingerprint} only groups
     */
    record Repeat(
            ProjectedRequest request,
            String fingerprint,
            String statement,
            int executions,
            long nanos,
            boolean unknownTime,
            boolean measured,
            Map<String, Integer> sites,
            String phase,
            String inTransaction,
            Map<Integer, Integer> issuers) {

        /**
         * The call site that issued most of its executions, preferring one after the handler on a tie, or {@code null}
         * when none was recorded. A statement run after the handler returned, while a view rendered or the response was
         * written, takes its render-time call site, such as the formatter a template called, not the handler's (M4-20's
         * adjudication follow-up 1).
         */
        String callSite() {
            String best = null;
            int most = 0;
            for (Map.Entry<String, Integer> site : sites.entrySet()) {
                boolean after = site.getKey().startsWith(AFTER_HANDLER);
                boolean bestAfter = best != null && best.startsWith(AFTER_HANDLER);
                if (site.getValue() > most || (site.getValue() == most && after && !bestAfter)) {
                    best = site.getKey();
                    most = site.getValue();
                }
            }
            if (best == null) {
                return null;
            }
            String site = best.startsWith(AFTER_HANDLER) ? best.substring(AFTER_HANDLER.length()) : best;
            return site.isEmpty() ? null : site;
        }

        /** {@code sites} with {@code sql}'s call site counted once more. */
        private static Map<String, Integer> site(Map<String, Integer> sites, SqlPayload sql) {
            String site = sql.phase() == RequestPhase.RESPONSE
                    ? AFTER_HANDLER + nonNull(LazySqlAfterHandler.callSite(sql))
                    : nonNull(sql.callSite());
            Map<String, Integer> merged = new LinkedHashMap<>(sites);
            merged.merge(site, 1, Integer::sum);
            return java.util.Collections.unmodifiableMap(merged);
        }

        private static String nonNull(String site) {
            return site == null ? "" : site;
        }

        static Repeat first(
                ProjectedRequest request,
                String fingerprint,
                RuntimeEvent event,
                SqlPayload sql,
                TransactionWindows windows) {
            long duration = event.durationNanos();
            boolean unknown = duration < 0;
            return new Repeat(
                    request,
                    fingerprint,
                    InsightText.statement(sql.sql()),
                    1,
                    unknown ? 0 : duration,
                    unknown,
                    duration > 0,
                    site(Map.of(), sql),
                    phaseLabel(sql.phase()),
                    transactionLabel(event, windows),
                    issuer(Map.of(), sql));
        }

        /** {@code issuers} with the method that issued {@code sql}, when its stamp names one. */
        private static Map<Integer, Integer> issuer(Map<Integer, Integer> issuers, SqlPayload sql) {
            int method = sql.codePathStamp() == 0L ? -1 : CodePathStamps.method(sql.codePathStamp());
            if (method < 0) {
                return issuers;
            }
            Map<Integer, Integer> merged = new LinkedHashMap<>(issuers);
            merged.merge(method, 1, Integer::sum);
            return java.util.Collections.unmodifiableMap(merged);
        }

        Repeat plus(RuntimeEvent event, SqlPayload sql, TransactionWindows windows) {
            long duration = event.durationNanos();
            boolean unknown = unknownTime || duration < 0;
            return new Repeat(
                    request,
                    fingerprint,
                    statement,
                    executions + 1,
                    unknown ? nanos : nanos + Math.max(0, duration),
                    unknown,
                    measured || duration > 0,
                    site(sites, sql),
                    merge(phase, phaseLabel(sql.phase())),
                    merge(inTransaction, transactionLabel(event, windows)),
                    issuer(issuers, sql));
        }
    }
}
