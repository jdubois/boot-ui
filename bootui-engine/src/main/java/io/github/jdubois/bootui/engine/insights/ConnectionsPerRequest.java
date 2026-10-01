package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code connections-per-request} ({@code docs/PLAN-v2.md} §5.5): one request holding two or more connections of one
 * data source at the same time, such as a {@code REQUIRES_NEW} transaction inside another. Intervals come from each
 * connection's monotonic checkout time and hold duration, so sequential connections never count as held together.
 */
public final class ConnectionsPerRequest implements Observation {

    public static final String KIND = "connections-per-request";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Connections held together";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.CONNECTION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, List<String[]>> byDataSource = new LinkedHashMap<>();
            Map<String, Integer> mostHeld = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                maxHeldTogether(request).forEach((dataSource, held) -> {
                    if (held >= 2) {
                        byDataSource
                                .computeIfAbsent(dataSource, d -> new ArrayList<>())
                                .add(new String[] {request.requestId(), String.valueOf(held)});
                        mostHeld.merge(dataSource, held, Math::max);
                    }
                });
            }
            byDataSource.forEach((dataSource, rows) -> {
                int held = mostHeld.get(dataSource);
                rows.sort(Comparator.comparingInt((String[] row) -> Integer.parseInt(row[1]))
                        .reversed());
                findings.add(new Finding(
                        route.getKey() + ":" + InsightText.stableHash(dataSource),
                        route.getKey(),
                        true,
                        "`" + route.getKey() + "` held " + held + " connections of `" + dataSource
                                + "` at the same time in " + rows.size() + " of "
                                + InsightText.counted(route.getValue().size(), "request") + ".",
                        route.getValue().size(),
                        rows.size(),
                        List.of(
                                "Check for a REQUIRES_NEW transaction, or a second call to the data source, inside"
                                        + " an open transaction (Transactions).",
                                "With a pool of P connections, (P − 1) ÷ (" + (held - 1) + ") such requests at once can"
                                        + " wait on each other until the pool times out: compare with the pool size"
                                        + " (Database Connection Pools)."),
                        rows.stream().limit(3).map(row -> row[0]).toList(),
                        List.of("Request", "Connections held together"),
                        rows.stream().map(List::of).toList(),
                        List.of("Counts logical connections the application obtained; the pool's size is not read"
                                + " here.")));
            });
        }
        return new Evaluation(snapshot.requests().size(), findings);
    }

    /** The most connections of each data source the request held at once. */
    static Map<String, Integer> maxHeldTogether(ProjectedRequest request) {
        Map<String, List<long[]>> intervals = new LinkedHashMap<>();
        for (RuntimeEvent event : request.children(JournalSource.CONNECTION)) {
            if (event.payload() instanceof ConnectionPayload connection
                    && connection.checkoutNanos() >= 0
                    && connection.dataSource() != null) {
                long start = connection.checkoutNanos();
                intervals
                        .computeIfAbsent(connection.dataSource(), d -> new ArrayList<>())
                        .add(new long[] {start, start + Math.max(0, event.durationNanos())});
            }
        }
        Map<String, Integer> most = new LinkedHashMap<>();
        intervals.forEach((dataSource, spans) -> {
            List<long[]> edges = new ArrayList<>();
            for (long[] span : spans) {
                edges.add(new long[] {span[0], 1});
                edges.add(new long[] {span[1], -1});
            }
            // At equal instants a release comes before a checkout, so back-to-back connections never overlap.
            edges.sort(Comparator.comparingLong((long[] edge) -> edge[0]).thenComparingLong(edge -> edge[1]));
            int open = 0;
            int max = 0;
            for (long[] edge : edges) {
                open += (int) edge[1];
                max = Math.max(max, open);
            }
            most.put(dataSource, max);
        });
        return most;
    }
}
