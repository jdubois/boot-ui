package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code slow-flush}: a route whose requests spent 100 ms or more in Hibernate flushes, full and automatic together,
 * and at least a fifth of their ORM time, in three requests or more. {@code large-persistence-context} names a likely
 * cause, entities to dirty-check; this reports the effect, the time flushing took. Flush time is the time in
 * Hibernate's flush callbacks without the statements the journal recorded a flush executing, so time waiting on the
 * database for those statements is not counted as flush time.
 */
public final class SlowFlush implements Observation {

    public static final String KIND = "slow-flush";

    /** The flush time, full and automatic together, that makes one request slow. */
    static final long MIN_FLUSH_NANOS = 100_000_000L;

    /** The share of a request's ORM time its flushes must take. */
    static final double MIN_SHARE = 0.2;

    /** The slow requests of a route that make a pattern. */
    static final int MIN_REQUESTS = 3;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Slow Hibernate flushes";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.ORM);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<OrmAutoFlush.Totals> slow = new ArrayList<>();
            long withOrm = 0;
            for (ProjectedRequest request : route.getValue()) {
                OrmAutoFlush.Totals totals = OrmAutoFlush.Totals.of(request);
                if (totals == null) {
                    continue;
                }
                withOrm++;
                if (isSlow(totals)) {
                    slow.add(totals);
                }
            }
            eligible += withOrm;
            if (slow.size() < MIN_REQUESTS) {
                continue;
            }
            slow.sort(Comparator.comparingLong(SlowFlush::flushNanos).reversed());
            String unit = InsightText.unit(route.getKey());
            findings.add(
                    new Finding(
                            route.getKey(),
                            route.getKey(),
                            true,
                            "`" + route.getKey() + "` spent up to " + InsightText.millis(flushNanos(slow.get(0)))
                                    + " ms flushing Hibernate's persistence context in one " + unit + ", in "
                                    + slow.size()
                                    + " of " + InsightText.counted(withOrm, unit) + ". Those " + slow.size()
                                    + " took at least 100 ms"
                                    + " and a fifth of their ORM time each.",
                            withOrm,
                            slow.size(),
                            List.of(
                                    "If the persistence context holds many entities (see `large-persistence-context`), load a"
                                            + " projection or DTO query instead of entities, or flush and clear it between"
                                            + " chunks (`EntityManager.clear()`).",
                                    "If the work only reads, make the transaction read-only"
                                            + " (`@Transactional(readOnly = true)` on Spring) so Hibernate skips dirty checking.",
                                    "If the time is in auto-flushes, run queries before changing entities, or give queries that"
                                            + " do not need pending changes the COMMIT flush mode"
                                            + " (`setHibernateFlushMode(FlushMode.COMMIT)`); `orm-auto-flush` reports"
                                            + " auto-flushes that wrote."),
                            slow.stream().limit(3).map(t -> t.requestId).toList(),
                            List.of(
                                    "Request",
                                    "Flush time (ms)",
                                    "Of which auto-flushes (ms)",
                                    "Most entities in a context"),
                            slow.stream()
                                    .map(t -> List.of(
                                            t.requestId,
                                            InsightText.millis(flushNanos(t)),
                                            InsightText.millis(t.autoFlushNanos),
                                            t.entities < 0 ? "unknown" : String.valueOf(t.entities)))
                                    .toList(),
                            List.of(
                                    "The flush timeline keeps only the first 16 flushes of a session; totals still include all.",
                                    "Flush time is the time in Hibernate's flush callbacks, full flushes and auto-flushes"
                                            + " together, without the statements the journal recorded; ORM time is that plus"
                                            + " the statements' time.",
                                    "Time waiting for a pooled connection or preparing a statement inside a flush counts as"
                                            + " flush time, and the entity count is the most any session of the request held.")));
        }
        return new Evaluation(eligible, findings);
    }

    private static boolean isSlow(OrmAutoFlush.Totals totals) {
        long flush = flushNanos(totals);
        return flush >= MIN_FLUSH_NANOS && flush >= MIN_SHARE * totals.ormNanos();
    }

    /** The request's full flushes and auto-flushes together. */
    private static long flushNanos(OrmAutoFlush.Totals totals) {
        return totals.flushNanos + totals.autoFlushNanos;
    }
}
