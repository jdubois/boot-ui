package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared grouping of already-correlated SQL executions by normalized statement text, with N+1 flagging
 * and bounded call-site aggregation.
 *
 * <p>Factored out of {@link SqlTraceRecorder#topStatements()} so the Live Activity per-request profile
 * (Spring's {@code LiveActivityCorrelator}, Quarkus's {@code RequestProfileAssembler}) and the
 * list-level N+1 badge (Spring's {@code LiveActivityService}, Quarkus's {@code LiveActivityAssembler})
 * apply the exact same definition of "looks like an N+1 access pattern" the global SQL Trace panel
 * uses, over an explicit, already-correlated subset of executions rather than the recorder's full
 * buffer. {@link SqlTraceRecorder#topStatements()} itself keeps its own separately-tested aggregation
 * over the ring buffer, but references {@link #MAX_CALL_SITES_PER_GROUP} so both paths bound call-site
 * accumulation identically.</p>
 */
public final class SqlTraceGrouping {

    /**
     * Default N+1 threshold used where no configurable {@code bootui.activity.n-plus-one-threshold}
     * exists (Quarkus's per-request profile and list badge; Spring reads its own configured value via
     * {@code BootUiProperties.getActivity().getNPlusOneThreshold()} instead).
     */
    public static final int DEFAULT_N_PLUS_ONE_THRESHOLD = 5;

    /** Maximum distinct call sites retained per group, most-recently-seen first. */
    public static final int MAX_CALL_SITES_PER_GROUP = 5;

    private SqlTraceGrouping() {}

    /**
     * Groups {@code entries} by normalized SQL text, ordered by execution count descending, flagging
     * repeated {@code SELECT}s at or above {@code nPlusOneThreshold} as a potential N+1 and aggregating
     * each group's distinct call sites (bounded by {@link #MAX_CALL_SITES_PER_GROUP}, in the order
     * {@code entries} were supplied; entries with no captured call site contribute none).
     */
    public static List<SqlTraceGroupDto> group(List<SqlTraceEntryDto> entries, int nPlusOneThreshold) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, Aggregate> byStatement = new LinkedHashMap<>();
        for (SqlTraceEntryDto entry : entries) {
            String key = normalizeSql(entry.sql());
            Aggregate aggregate = byStatement.computeIfAbsent(key, k -> new Aggregate(k, entry.category()));
            aggregate.executions++;
            aggregate.totalDuration += entry.durationMillis();
            aggregate.maxDuration = Math.max(aggregate.maxDuration, entry.durationMillis());
            aggregate.addCallSite(entry.callSite());
        }
        List<SqlTraceGroupDto> groups = new ArrayList<>();
        for (Aggregate aggregate : byStatement.values()) {
            boolean nPlusOne =
                    "SELECT".equalsIgnoreCase(aggregate.category) && aggregate.executions >= nPlusOneThreshold;
            groups.add(new SqlTraceGroupDto(
                    aggregate.sql,
                    aggregate.category,
                    aggregate.executions,
                    aggregate.totalDuration,
                    aggregate.maxDuration,
                    nPlusOne,
                    aggregate.callSites()));
        }
        groups.sort(Comparator.comparingLong(SqlTraceGroupDto::executions).reversed());
        return groups;
    }

    /** Convenience check for a list-level flag: whether any group in {@code entries} looks like an N+1. */
    public static boolean anySuspectedNPlusOne(List<SqlTraceEntryDto> entries, int nPlusOneThreshold) {
        return group(entries, nPlusOneThreshold).stream().anyMatch(SqlTraceGroupDto::potentialNPlusOne);
    }

    /** Collapse runs of whitespace into single spaces and trim, returning "" for null. */
    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static final class Aggregate {
        private final String sql;
        private final String category;
        private long executions;
        private long totalDuration;
        private long maxDuration;
        private final Set<String> callSites = new LinkedHashSet<>();

        private Aggregate(String sql, String category) {
            this.sql = sql;
            this.category = category == null || category.isBlank() ? "OTHER" : category;
        }

        private void addCallSite(String callSite) {
            if (callSite != null && callSites.size() < MAX_CALL_SITES_PER_GROUP) {
                callSites.add(callSite);
            }
        }

        private List<String> callSites() {
            return List.copyOf(callSites);
        }
    }
}
