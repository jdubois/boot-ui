package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code orm-auto-flush} ({@code docs/PLAN-v2.md} §5.18, M4-9): Hibernate writing pending changes before the queries of
 * one request, three times or more, or for at least a fifth of the request's ORM time. Each such auto-flush means the
 * request modified entities and then queried again in the same transaction. The threshold is per request, as §5.18
 * sets it, so one flagged request of a route is enough to report it.
 */
public final class OrmAutoFlush implements Observation {

    public static final String KIND = "orm-auto-flush";
    static final int MIN_AUTO_FLUSHES = 3;
    static final double MIN_SHARE = 0.2;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Hibernate auto-flushes";
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
            List<Totals> flagged = new ArrayList<>();
            long withOrm = 0;
            for (ProjectedRequest request : route.getValue()) {
                Totals totals = Totals.of(request);
                if (totals == null) {
                    continue;
                }
                withOrm++;
                if (totals.autoFlushes > 0
                        && (totals.autoFlushes >= MIN_AUTO_FLUSHES
                                || totals.autoFlushNanos >= MIN_SHARE * totals.ormNanos())) {
                    flagged.add(totals);
                }
            }
            eligible += withOrm;
            if (flagged.isEmpty()) {
                continue;
            }
            flagged.sort(Comparator.comparingInt((Totals t) -> t.autoFlushes).reversed());
            int most = flagged.get(0).autoFlushes;
            String unit = InsightText.unit(route.getKey());
            findings.add(new Finding(
                    route.getKey(),
                    route.getKey(),
                    true,
                    "`" + route.getKey() + "` made Hibernate write pending changes before a query up to " + most
                            + (most == 1 ? " time" : " times") + " in one " + unit + ", in " + flagged.size() + " of "
                            + InsightText.counted(withOrm, unit) + ".",
                    withOrm,
                    flagged.size(),
                    List.of(
                            "Run the queries before modifying entities, or move the writes after the reads.",
                            "If a query does not read the changed entities, give it the COMMIT flush mode"
                                    + " (`setHibernateFlushMode(FlushMode.COMMIT)` or `FlushModeType.COMMIT`)."),
                    flagged.stream().limit(3).map(t -> t.requestId).toList(),
                    List.of("Request", "Auto-flushes that wrote", "Auto-flushing (ms)", "ORM time (ms)"),
                    flagged.stream()
                            .map(t -> List.of(
                                    t.requestId,
                                    String.valueOf(t.autoFlushes),
                                    InsightText.millis(t.autoFlushNanos),
                                    InsightText.millis(t.ormNanos())))
                            .toList(),
                    List.of(
                            "Counts auto-flushes that executed a statement; checks that found nothing to write add"
                                    + " time only.",
                            "ORM time is Hibernate's flush time plus the statements it executed.")));
        }
        return new Evaluation(eligible, findings);
    }

    /** One request's Hibernate work, summed over its sessions. */
    static final class Totals {

        final String requestId;
        int autoFlushes;
        long autoFlushNanos;
        long flushNanos;
        long statementNanos;
        int entities = -1;

        private Totals(String requestId) {
            this.requestId = requestId;
        }

        long ormNanos() {
            return autoFlushNanos + flushNanos + statementNanos;
        }

        /** The request's totals, or {@code null} when no Hibernate session of it was recorded. */
        static Totals of(ProjectedRequest request) {
            Totals totals = null;
            for (RuntimeEvent event : request.children(JournalSource.ORM)) {
                if (event.payload() instanceof OrmPayload orm) {
                    if (totals == null) {
                        totals = new Totals(request.requestId());
                    }
                    totals.autoFlushes += orm.partialFlushes();
                    totals.autoFlushNanos += Math.max(0, orm.partialFlushNanos());
                    totals.flushNanos += Math.max(0, orm.flushNanos());
                    totals.statementNanos += Math.max(0, orm.statementNanos());
                    totals.entities = Math.max(totals.entities, orm.entitiesInContext());
                }
            }
            return totals;
        }
    }
}
