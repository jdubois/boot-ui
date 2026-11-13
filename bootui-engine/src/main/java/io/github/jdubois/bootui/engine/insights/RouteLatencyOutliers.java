package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.support.Percentiles;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code route-latency-outliers}: compares a route's slowest request against its retained warm {@code 2xx} baseline
 * request closest to the route's warm median, when the slowest request is at least {@value #MIN_OUTLIER_MULTIPLIER}x
 * the warm median and at least 100 ms slower and differs in recorded child work (SQL executions or shapes, connection
 * wait, cache hits/misses, outbound REST client calls, or Hibernate flushes/entities).
 *
 * <p>To keep evaluation fast across large journals, each route is pre-filtered using primitive durations and statuses
 * first; child events are inspected for at most two requests per affected route (the slowest outlier and the warm
 * {@code 2xx} baseline closest to the warm median), and SQL statements are grouped by their cached
 * {@link SqlShapes#fingerprint(String)} before formatting at most {@value #MAX_DISTINGUISHING_SQL_SHAPES}
 * literal-free shapes via {@link JournalTextExposure#displayShape(String)}.</p>
 */
public final class RouteLatencyOutliers implements Observation {

    public static final String KIND = "route-latency-outliers";

    /** The warm 2xx requests a route needs before an outlier comparison is reported as sufficient. */
    static final int MIN_WARM_REQUESTS = 5;

    /** The fewest warm 2xx requests needed to form a preliminary comparison. */
    static final int MIN_PRELIMINARY_WARM_REQUESTS = 2;

    /** How many times the warm median an outlier request must take. */
    static final int MIN_OUTLIER_MULTIPLIER = 4;

    /** How much longer than the warm median an outlier request must take (100 ms). */
    static final long MIN_OUTLIER_DELTA_NANOS = 100_000_000L;

    /** The most distinguishing SQL shapes listed in the evidence table. */
    static final int MAX_DISTINGUISHING_SQL_SHAPES = 5;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Slow route outliers vs. baseline";
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
                JournalSource.SQL,
                JournalSource.CONNECTION,
                JournalSource.CACHE,
                JournalSource.REST_CLIENT,
                JournalSource.ORM);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> entry :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> requests = entry.getValue();
            if (requests.size() <= 1) {
                continue;
            }
            // Leave out the first retained request on the route so the cold request is never chosen as baseline or
            // outlier.
            List<ProjectedRequest> warm = requests.subList(1, requests.size());
            eligible += warm.size();

            long[] warm2xxNanos = new long[warm.size()];
            int warm2xxCount = 0;
            ProjectedRequest slowest = null;
            for (ProjectedRequest req : warm) {
                if (req.status() >= 200 && req.status() < 300) {
                    warm2xxNanos[warm2xxCount++] = req.durationNanos();
                }
                if (slowest == null || req.durationNanos() >= slowest.durationNanos()) {
                    slowest = req;
                }
            }
            if (warm2xxCount < MIN_PRELIMINARY_WARM_REQUESTS || slowest == null) {
                continue;
            }
            Arrays.sort(warm2xxNanos, 0, warm2xxCount);
            long warmP50Nanos = warm2xxNanos[Percentiles.nearestRankIndex(warm2xxCount, 50)];
            long thresholdNanos =
                    Math.max(warmP50Nanos * MIN_OUTLIER_MULTIPLIER, warmP50Nanos + MIN_OUTLIER_DELTA_NANOS);
            if (slowest.durationNanos() < thresholdNanos) {
                continue;
            }

            ProjectedRequest baseline = null;
            long bestDistance = Long.MAX_VALUE;
            int outlierCount = 0;
            for (ProjectedRequest req : warm) {
                if (req.durationNanos() >= thresholdNanos) {
                    outlierCount++;
                }
                if (req == slowest || req.status() < 200 || req.status() >= 300) {
                    continue;
                }
                long distance = Math.abs(req.durationNanos() - warmP50Nanos);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    baseline = req;
                }
            }
            if (baseline == null) {
                continue;
            }

            OutlierMetrics targetMetrics = OutlierMetrics.of(slowest);
            OutlierMetrics baselineMetrics = OutlierMetrics.of(baseline);
            DiffSummary diff = compare(slowest, baseline, warmP50Nanos, targetMetrics, baselineMetrics, snapshot);
            if (!diff.hasChildDiff()) {
                continue;
            }

            List<String> limitations = new ArrayList<>();
            if (!snapshot.firstRequestIsCold()) {
                limitations.add("Events were evicted or cleared, so the route's first retained request may not be its"
                        + " cold first request in this run.");
            }
            if (targetMetrics.hasUnverifiedSql || baselineMetrics.hasUnverifiedSql) {
                limitations.add("Unverified SQL preparations do not establish JDBC execution counts or database time.");
            }

            String route = entry.getKey();
            boolean sufficient = warm2xxCount >= MIN_WARM_REQUESTS;
            String sentence = sufficient
                    ? "On `" + route + "`, slowest request `" + slowest.requestId() + "` took "
                            + InsightText.millis(slowest.durationNanos()) + " ms against "
                            + InsightText.millis(baseline.durationNanos()) + " ms in warm baseline `"
                            + baseline.requestId() + "` (warm median " + InsightText.millis(warmP50Nanos)
                            + " ms across " + InsightText.counted(warm2xxCount, "warm 2xx request") + "): "
                            + String.join(", ", diff.highlights()) + "."
                    : "On `" + route + "`, request `" + slowest.requestId() + "` took "
                            + InsightText.millis(slowest.durationNanos()) + " ms against "
                            + InsightText.millis(baseline.durationNanos()) + " ms in `" + baseline.requestId()
                            + "` (" + String.join(", ", diff.highlights()) + "), over "
                            + InsightText.counted(warm2xxCount, "warm 2xx request")
                            + "; comparing against a warm baseline needs " + MIN_WARM_REQUESTS + ".";

            findings.add(new Finding(
                    route,
                    route,
                    sufficient,
                    sentence,
                    warm.size(),
                    outlierCount,
                    List.of(
                            "Open the outlier request in Live Activity to inspect its timeline and the Compared with"
                                    + " baseline request card.",
                            "Compare the distinguishing SQL shapes, cache misses, and outbound calls between the"
                                    + " outlier and the baseline request."),
                    List.of(slowest.requestId(), baseline.requestId()),
                    List.of("Dimension", "Outlier", "Baseline", "Delta"),
                    diff.rows(),
                    limitations));
        }
        return new Evaluation(eligible, findings);
    }

    private static DiffSummary compare(
            ProjectedRequest slowest,
            ProjectedRequest baseline,
            long warmP50Nanos,
            OutlierMetrics target,
            OutlierMetrics base,
            InsightsSnapshot snapshot) {
        List<String> highlights = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(
                "Request",
                slowest.requestId() + " (" + slowest.status() + ")",
                baseline.requestId() + " (" + baseline.status() + ")",
                signedMillis(slowest.durationNanos() - baseline.durationNanos()) + " ms"));
        rows.add(List.of(
                "Duration (ms)",
                InsightText.millis(slowest.durationNanos()),
                InsightText.millis(baseline.durationNanos()) + " (warm p50 " + InsightText.millis(warmP50Nanos) + ")",
                signedMillis(slowest.durationNanos() - baseline.durationNanos())));

        boolean sqlVerified = snapshot.available(JournalSource.SQL)
                && !target.hasUnverifiedSql
                && !base.hasUnverifiedSql
                && (target.sqlExecutions > 0 || base.sqlExecutions > 0);
        if (sqlVerified) {
            int execDelta = target.sqlExecutions - base.sqlExecutions;
            long nanosDelta = target.sqlDurationNanos - base.sqlDurationNanos;
            List<ShapeDiff> shapeDiffs = distinguishingShapes(target, base);
            if (execDelta != 0 || nanosDelta != 0 || !shapeDiffs.isEmpty()) {
                rows.add(List.of(
                        "SQL executions",
                        target.sqlExecutions + " (" + InsightText.millis(target.sqlDurationNanos) + " ms)",
                        base.sqlExecutions + " (" + InsightText.millis(base.sqlDurationNanos) + " ms)",
                        signedInt(execDelta) + " (" + signedMillis(nanosDelta) + " ms)"));
                if (execDelta > 0) {
                    highlights.add("+" + InsightText.counted(execDelta, "SQL execution") + " ("
                            + signedMillis(nanosDelta) + " ms)");
                } else if (nanosDelta >= 10_000_000L) {
                    highlights.add(signedMillis(nanosDelta) + " ms in SQL");
                }
                for (ShapeDiff shape : shapeDiffs) {
                    rows.add(List.of(
                            "Distinguishing SQL",
                            shape.displayShape(),
                            shape.baselineCount() + " execution(s)",
                            signedInt(shape.countDelta())));
                }
                if (highlights.isEmpty() && !shapeDiffs.isEmpty()) {
                    highlights.add(
                            "different SQL query shape `" + shapeDiffs.get(0).displayShape() + "`");
                }
            }
        }

        if (snapshot.available(JournalSource.CONNECTION) && (target.hasConnection || base.hasConnection)) {
            long waitDelta = target.connectionWaitNanos - base.connectionWaitNanos;
            if (waitDelta != 0) {
                rows.add(List.of(
                        "Connection wait (ms)",
                        InsightText.millis(target.connectionWaitNanos),
                        InsightText.millis(base.connectionWaitNanos),
                        signedMillis(waitDelta)));
                if (waitDelta >= 5_000_000L) {
                    highlights.add(signedMillis(waitDelta) + " ms connection wait");
                }
            }
        }

        if (snapshot.available(JournalSource.CACHE) && (target.hasCache || base.hasCache)) {
            int missDelta = target.cacheMisses - base.cacheMisses;
            int hitDelta = target.cacheHits - base.cacheHits;
            if (missDelta != 0 || hitDelta != 0) {
                rows.add(List.of(
                        "Cache (misses / hits)",
                        target.cacheMisses + " / " + target.cacheHits,
                        base.cacheMisses + " / " + base.cacheHits,
                        signedInt(missDelta) + " misses, " + signedInt(hitDelta) + " hits"));
                if (missDelta > 0) {
                    highlights.add("+" + InsightText.counted(missDelta, "cache miss", "cache misses"));
                }
            }
        }

        if (snapshot.available(JournalSource.REST_CLIENT) && (target.restClientCalls > 0 || base.restClientCalls > 0)) {
            int callDelta = target.restClientCalls - base.restClientCalls;
            long nanosDelta = target.restClientDurationNanos - base.restClientDurationNanos;
            if (callDelta != 0 || nanosDelta != 0) {
                rows.add(List.of(
                        "REST client calls",
                        target.restClientCalls + " (" + InsightText.millis(target.restClientDurationNanos) + " ms)",
                        base.restClientCalls + " (" + InsightText.millis(base.restClientDurationNanos) + " ms)",
                        signedInt(callDelta) + " (" + signedMillis(nanosDelta) + " ms)"));
                if (callDelta > 0) {
                    highlights.add("+" + InsightText.counted(callDelta, "REST client call") + " ("
                            + signedMillis(nanosDelta) + " ms)");
                } else if (nanosDelta >= 10_000_000L) {
                    highlights.add(signedMillis(nanosDelta) + " ms in REST client calls");
                }
            }
        }

        if (snapshot.available(JournalSource.ORM) && (target.hasOrm || base.hasOrm)) {
            int flushDelta = target.ormFlushes - base.ormFlushes;
            int autoFlushDelta = target.ormAutoFlushes - base.ormAutoFlushes;
            if (flushDelta != 0 || autoFlushDelta != 0) {
                rows.add(List.of(
                        "Hibernate (flushes / auto-flushes)",
                        target.ormFlushes + " / " + target.ormAutoFlushes,
                        base.ormFlushes + " / " + base.ormAutoFlushes,
                        signedInt(flushDelta) + " flushes, " + signedInt(autoFlushDelta) + " auto-flushes"));
                if (autoFlushDelta > 0) {
                    highlights.add("+" + InsightText.counted(autoFlushDelta, "auto-flush", "auto-flushes"));
                } else if (flushDelta > 0) {
                    highlights.add("+" + InsightText.counted(flushDelta, "Hibernate flush", "Hibernate flushes"));
                }
            }
            if (!target.ormEntitiesUnmeasured
                    && !base.ormEntitiesUnmeasured
                    && (target.ormEntitiesMeasured || base.ormEntitiesMeasured)) {
                int entityDelta = target.ormMaxEntities - base.ormMaxEntities;
                if (entityDelta != 0) {
                    rows.add(List.of(
                            "Peak entities in context",
                            String.valueOf(target.ormMaxEntities),
                            String.valueOf(base.ormMaxEntities),
                            signedInt(entityDelta)));
                    if (entityDelta > 0 && highlights.isEmpty()) {
                        highlights.add("+" + InsightText.counted(entityDelta, "entity", "entities") + " in context");
                    }
                }
            }
        }

        return new DiffSummary(!highlights.isEmpty(), highlights, rows);
    }

    private static List<ShapeDiff> distinguishingShapes(OutlierMetrics target, OutlierMetrics base) {
        record Candidate(
                String fingerprint,
                boolean onlyInTarget,
                int countDelta,
                int baselineCount,
                long targetNanos,
                int order) {}
        List<Candidate> candidates = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, int[]> entry : target.sqlFingerprints.entrySet()) {
            String fp = entry.getKey();
            int targetCount = entry.getValue()[0];
            int baselineCount = base.sqlFingerprints.getOrDefault(fp, new int[1])[0];
            int delta = targetCount - baselineCount;
            if (delta > 0) {
                long targetNanos = target.sqlFingerprintNanos.getOrDefault(fp, 0L);
                candidates.add(new Candidate(fp, baselineCount == 0, delta, baselineCount, targetNanos, index));
            }
            index++;
        }
        candidates.sort((left, right) -> {
            if (left.onlyInTarget() != right.onlyInTarget()) {
                return left.onlyInTarget() ? -1 : 1;
            }
            int byDelta = Integer.compare(right.countDelta(), left.countDelta());
            if (byDelta != 0) {
                return byDelta;
            }
            int byNanos = Long.compare(right.targetNanos(), left.targetNanos());
            if (byNanos != 0) {
                return byNanos;
            }
            return Integer.compare(left.order(), right.order());
        });
        Map<String, ShapeDiff> deduplicated = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (deduplicated.size() >= MAX_DISTINGUISHING_SQL_SHAPES) {
                break;
            }
            String rawSql = target.rawSqlByFingerprint.getOrDefault(candidate.fingerprint(), candidate.fingerprint());
            String displayShape = JournalTextExposure.displayShape(rawSql);
            if (!displayShape.isBlank()) {
                deduplicated.putIfAbsent(
                        displayShape, new ShapeDiff(displayShape, candidate.baselineCount(), candidate.countDelta()));
            }
        }
        return List.copyOf(deduplicated.values());
    }

    private static String signedInt(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String signedMillis(long nanos) {
        return (nanos >= 0 ? "+" : "-") + InsightText.millis(Math.abs(nanos));
    }

    private record ShapeDiff(String displayShape, int baselineCount, int countDelta) {}

    private record DiffSummary(boolean hasChildDiff, List<String> highlights, List<List<String>> rows) {}

    private static final class OutlierMetrics {
        int sqlExecutions;
        long sqlDurationNanos;
        boolean hasUnverifiedSql;
        final Map<String, int[]> sqlFingerprints = new LinkedHashMap<>();
        final Map<String, Long> sqlFingerprintNanos = new HashMap<>();
        final Map<String, String> rawSqlByFingerprint = new HashMap<>();
        boolean hasConnection;
        long connectionWaitNanos;
        boolean hasCache;
        int cacheHits;
        int cacheMisses;
        int restClientCalls;
        long restClientDurationNanos;
        boolean hasOrm;
        int ormFlushes;
        int ormAutoFlushes;
        boolean ormEntitiesMeasured;
        boolean ormEntitiesUnmeasured;
        int ormMaxEntities;

        static OutlierMetrics of(ProjectedRequest request) {
            OutlierMetrics metrics = new OutlierMetrics();
            for (RuntimeEvent event : request.children()) {
                RuntimeEventPayload payload = event.payload();
                if (payload instanceof SqlPayload sql) {
                    if (sql.executed()) {
                        metrics.sqlExecutions++;
                        long nanos = Math.max(0, event.durationNanos());
                        metrics.sqlDurationNanos += nanos;
                        String fingerprint = SqlShapes.fingerprint(sql.sql());
                        if (fingerprint != null && !fingerprint.isBlank()) {
                            metrics.sqlFingerprints.computeIfAbsent(fingerprint, key -> new int[1])[0]++;
                            metrics.sqlFingerprintNanos.merge(fingerprint, nanos, Long::sum);
                            metrics.rawSqlByFingerprint.putIfAbsent(fingerprint, sql.sql());
                        }
                    } else {
                        metrics.hasUnverifiedSql = true;
                    }
                } else if (payload instanceof ConnectionPayload connection) {
                    metrics.hasConnection = true;
                    metrics.connectionWaitNanos += Math.max(0, connection.waitNanos());
                } else if (payload instanceof CachePayload cache) {
                    metrics.hasCache = true;
                    if ("HIT".equalsIgnoreCase(cache.operation())) {
                        metrics.cacheHits++;
                    } else if ("MISS".equalsIgnoreCase(cache.operation())) {
                        metrics.cacheMisses++;
                    }
                } else if (payload instanceof RestClientPayload) {
                    metrics.restClientCalls++;
                    metrics.restClientDurationNanos += Math.max(0, event.durationNanos());
                } else if (payload instanceof OrmPayload orm) {
                    metrics.hasOrm = true;
                    metrics.ormFlushes += orm.flushes();
                    metrics.ormAutoFlushes += orm.partialFlushes();
                    if (orm.entitiesInContext() >= 0) {
                        metrics.ormEntitiesMeasured = true;
                        metrics.ormMaxEntities = Math.max(metrics.ormMaxEntities, orm.entitiesInContext());
                    } else {
                        metrics.ormEntitiesUnmeasured = true;
                    }
                }
            }
            return metrics;
        }
    }
}
