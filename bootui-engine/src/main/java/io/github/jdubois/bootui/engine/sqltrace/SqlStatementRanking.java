package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlStatementRankingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Ranks normalized statements over the retained SQL Trace window.
 *
 * <p>Ranking is a read over evidence BootUI already holds: it groups the retained executions by their
 * normalized, literal-free text (see {@link SqlStatementNormalizer}) and reports, for every group, the
 * metrics a developer wants to sort by — see {@link Criterion}.</p>
 *
 * <p>Rather than returning one ranked list per criterion, the service returns the <em>union</em> of the
 * top {@link #TOP_PER_CRITERION} groups for each criterion, with every metric on each row. That is exact:
 * re-sorting the returned rows by any supported criterion yields that criterion's true top
 * {@link #TOP_PER_CRITERION}, while the response stays bounded at {@link Criterion} count times that limit
 * no matter how many distinct statements the application executes. Each row states which criteria it was
 * selected for, so a consumer that is not the panel can tell a real ranking from the rest of the union.</p>
 *
 * <p>A criterion only ever admits groups whose value for it is greater than zero. Without that, a window
 * with no failures would still produce a ten-row "top statements by error count", every row reporting zero
 * errors and ordered by nothing a reader could see.</p>
 *
 * <p>Ties are broken deterministically by the statement fingerprint, so two statements with identical
 * metrics always order the same way across runs and across adapters instead of following buffer order.</p>
 */
public final class SqlStatementRanking {

    /** Groups returned for each ranking criterion; the response holds the union across criteria. */
    public static final int TOP_PER_CRITERION = 10;

    /**
     * The criteria a statement ranking can be ordered by. The percentile criteria matter because a
     * statement that is usually fast but occasionally pathological is invisible in every other criterion:
     * its total is modest, its average is diluted by the fast executions, and its maximum may be a single
     * outlier the tail already describes better.
     */
    public enum Criterion {
        TOTAL_DURATION(SqlStatementAggregate::totalDurationMicros),
        MAX_DURATION(SqlStatementAggregate::maxDurationMicros),
        EXECUTIONS(SqlStatementAggregate::executions),
        AVG_DURATION(SqlStatementAggregate::avgDurationMicros),
        ERROR_COUNT(SqlStatementAggregate::errorCount),
        P95_DURATION(aggregate -> aggregate.percentileMicros(95)),
        P99_DURATION(aggregate -> aggregate.percentileMicros(99));

        private final ToDoubleFunction<SqlStatementAggregate> metric;

        Criterion(ToDoubleFunction<SqlStatementAggregate> metric) {
            this.metric = metric;
        }
    }

    /** Upper bound on ranked rows in one report, stated so callers can rely on it. */
    public static final int MAX_RANKED_STATEMENTS = TOP_PER_CRITERION * 7;

    private SqlStatementRanking() {}

    /**
     * The ranked statements plus the bounding facts a reader needs to interpret them.
     *
     * @param statements the union of each criterion's top groups, ordered by cumulative duration
     * @param truncated whether distinct statements exist beyond {@code statements}
     * @param distinct distinct normalized statements observed in the window
     * @param totalDurationMicros total retained database time in microseconds, the denominator of every share
     */
    public record Ranked(
            List<SqlStatementRankingDto> statements, boolean truncated, int distinct, long totalDurationMicros) {

        public Ranked {
            statements = statements == null ? List.of() : List.copyOf(statements);
        }

        static Ranked empty() {
            return new Ranked(List.of(), false, 0, 0);
        }
    }

    /** Groups {@code entries} by normalized statement, preserving encounter order for stable display. */
    static Map<String, SqlStatementAggregate> aggregate(List<SqlTraceEntryDto> entries) {
        Map<String, SqlStatementAggregate> byFingerprint = new LinkedHashMap<>();
        if (entries == null) {
            return byFingerprint;
        }
        for (SqlTraceEntryDto entry : entries) {
            SqlStatementNormalizer.Result normalized = SqlStatementNormalizer.normalize(entry.sql());
            byFingerprint
                    .computeIfAbsent(
                            normalized.fingerprint(),
                            key -> new SqlStatementAggregate(key, normalized.sql(), entry.category()))
                    .add(entry);
        }
        return byFingerprint;
    }

    /**
     * Ranks the retained executions.
     *
     * @param entries the retained executions, already masked by the caller
     * @param nPlusOneThreshold repetition count at which a repeated {@code SELECT} is flagged as a
     *     possible N+1, using the same definition as the rest of the panel
     */
    public static Ranked rank(List<SqlTraceEntryDto> entries, int nPlusOneThreshold) {
        Map<String, SqlStatementAggregate> byFingerprint = aggregate(entries);
        if (byFingerprint.isEmpty()) {
            return Ranked.empty();
        }
        long totalDurationMicros = byFingerprint.values().stream()
                .mapToLong(SqlStatementAggregate::totalDurationMicros)
                .sum();

        List<SqlStatementAggregate> all = new ArrayList<>(byFingerprint.values());
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (Criterion criterion : Criterion.values()) {
            selectTop(all, selected, criterion);
        }

        List<SqlStatementRankingDto> ranked = all.stream()
                .filter(aggregate -> selected.containsKey(aggregate.fingerprint()))
                .sorted(byMetric(SqlStatementAggregate::totalDurationMicros))
                .map(aggregate -> toDto(
                        aggregate,
                        List.copyOf(selected.get(aggregate.fingerprint())),
                        totalDurationMicros,
                        nPlusOneThreshold))
                .toList();
        return new Ranked(ranked, byFingerprint.size() > ranked.size(), byFingerprint.size(), totalDurationMicros);
    }

    private static void selectTop(
            List<SqlStatementAggregate> all, Map<String, Set<String>> selected, Criterion criterion) {
        all.stream()
                .filter(aggregate -> criterion.metric.applyAsDouble(aggregate) > 0)
                .sorted(byMetric(criterion.metric))
                .limit(TOP_PER_CRITERION)
                .forEach(aggregate -> selected.computeIfAbsent(aggregate.fingerprint(), key -> new LinkedHashSet<>())
                        .add(criterion.name()));
    }

    /** Highest metric first, then fingerprint ascending so ties never depend on buffer order. */
    private static Comparator<SqlStatementAggregate> byMetric(ToDoubleFunction<SqlStatementAggregate> metric) {
        return Comparator.comparingDouble(metric).reversed().thenComparing(SqlStatementAggregate::fingerprint);
    }

    private static SqlStatementRankingDto toDto(
            SqlStatementAggregate aggregate, List<String> topFor, long totalDurationMicros, int nPlusOneThreshold) {
        return new SqlStatementRankingDto(
                aggregate.fingerprint(),
                aggregate.sql(),
                aggregate.category(),
                aggregate.executions(),
                SqlDurations.millis(aggregate.totalDurationMicros()),
                SqlDurations.millis(aggregate.maxDurationMicros()),
                SqlDurations.millis(aggregate.avgDurationMicros()),
                aggregate.errorCount(),
                SqlDurations.millis(aggregate.percentileMicros(50)),
                SqlDurations.millis(aggregate.percentileMicros(95)),
                SqlDurations.millis(aggregate.percentileMicros(99)),
                SqlShares.percent(aggregate.totalDurationMicros(), totalDurationMicros),
                topFor,
                "SELECT".equalsIgnoreCase(aggregate.category()) && aggregate.executions() >= nPlusOneThreshold,
                aggregate.callSites(),
                aggregate.entryIds(),
                aggregate.entryIdsTruncated());
    }
}
