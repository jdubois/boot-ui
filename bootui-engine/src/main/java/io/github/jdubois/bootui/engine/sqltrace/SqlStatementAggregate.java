package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable accumulator for one normalized statement, shared by the global ranking and by the per-route
 * breakdown so both agree exactly on what an execution contributes.
 *
 * <p>Durations are accumulated in microseconds, the resolution they are captured at, so a window of
 * sub-millisecond executions still totals the database time it really spent. They are retained per group so percentiles are computed from the real distribution rather than
 * estimated from a mean. That is affordable because the source buffer is already bounded: across every
 * group the accumulated durations total exactly the number of retained executions.</p>
 */
final class SqlStatementAggregate {

    /**
     * Retained execution ids linked from one ranked group, so the panel can filter the executions table to
     * exactly this statement instead of guessing with a text search. An execution belongs to one statement
     * group and one route group, so the ids shipped in a report are bounded by the retained window; the cap
     * only guards a deliberately huge {@code max-entries}.
     */
    static final int MAX_LINKED_ENTRIES = 500;

    private final String fingerprint;
    private final String sql;
    private final String category;
    private final List<Long> durationsMicros = new ArrayList<>();
    private final Set<String> callSites = new LinkedHashSet<>();
    private final List<Long> entryIds = new ArrayList<>();
    private List<Long> sortedDurationsMicros;
    private long executions;
    private long totalDurationMicros;
    private long maxDurationMicros;
    private long errorCount;

    SqlStatementAggregate(String fingerprint, String sql, String category) {
        this.fingerprint = fingerprint;
        this.sql = sql;
        this.category = category == null || category.isBlank() ? "OTHER" : category;
    }

    void add(SqlTraceEntryDto entry) {
        long duration = Math.max(0, entry.durationMicros());
        executions++;
        totalDurationMicros += duration;
        maxDurationMicros = Math.max(maxDurationMicros, duration);
        durationsMicros.add(duration);
        sortedDurationsMicros = null;
        if (entryIds.size() < MAX_LINKED_ENTRIES) {
            entryIds.add(entry.id());
        }
        if (!entry.success()) {
            errorCount++;
        }
        if (entry.callSite() != null && callSites.size() < SqlTraceGrouping.MAX_CALL_SITES_PER_GROUP) {
            callSites.add(entry.callSite());
        }
    }

    String fingerprint() {
        return fingerprint;
    }

    String sql() {
        return sql;
    }

    String category() {
        return category;
    }

    long executions() {
        return executions;
    }

    long totalDurationMicros() {
        return totalDurationMicros;
    }

    long maxDurationMicros() {
        return maxDurationMicros;
    }

    long errorCount() {
        return errorCount;
    }

    double avgDurationMicros() {
        return executions == 0 ? 0 : (double) totalDurationMicros / executions;
    }

    List<String> callSites() {
        return List.copyOf(callSites);
    }

    List<Long> entryIds() {
        return List.copyOf(entryIds);
    }

    /** Whether {@link #entryIds()} covers only part of the group, so a deep link can say so. */
    boolean entryIdsTruncated() {
        return entryIds.size() < executions;
    }

    /**
     * Nearest-rank percentile over this group's retained durations. Exact for the window it describes, so
     * the panel can honestly label these "over the retained window" rather than as sampled estimates. The
     * sorted view is memoized because every ranked row asks for three percentiles from the same group.
     */
    long percentileMicros(int percentile) {
        if (durationsMicros.isEmpty()) {
            return 0;
        }
        List<Long> sorted = sortedDurationsMicros;
        if (sorted == null) {
            sorted = new ArrayList<>(durationsMicros);
            sorted.sort(null);
            sortedDurationsMicros = sorted;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
        return sorted.get(index);
    }
}
