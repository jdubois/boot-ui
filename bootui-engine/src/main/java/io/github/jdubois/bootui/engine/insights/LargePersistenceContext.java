package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code large-persistence-context} ({@code docs/PLAN-v2.md} §5.18, M4-9): a route whose Hibernate sessions held 500
 * entities or more at a flush in three requests or more. Every managed entity is dirty-checked at each flush and kept in
 * memory until the session ends.
 */
public final class LargePersistenceContext implements Observation {

    public static final String KIND = "large-persistence-context";
    static final int MIN_ENTITIES = 500;
    static final int MIN_REQUESTS = 3;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Large persistence contexts";
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
            List<OrmAutoFlush.Totals> large = new ArrayList<>();
            long measured = 0;
            for (ProjectedRequest request : route.getValue()) {
                OrmAutoFlush.Totals totals = OrmAutoFlush.Totals.of(request);
                if (totals == null || totals.entities < 0) {
                    continue;
                }
                measured++;
                if (totals.entities >= MIN_ENTITIES) {
                    large.add(totals);
                }
            }
            eligible += measured;
            if (large.isEmpty()) {
                continue;
            }
            large.sort(Comparator.comparingInt((OrmAutoFlush.Totals t) -> t.entities)
                    .reversed());
            String unit = InsightText.unit(route.getKey());
            boolean sufficient = large.size() >= MIN_REQUESTS;
            findings.add(new Finding(
                    route.getKey(),
                    route.getKey(),
                    sufficient,
                    "`" + route.getKey() + "` held up to " + large.get(0).entities
                            + " entities in its persistence context in " + large.size() + " of "
                            + InsightText.counted(measured, unit) + "."
                            + (sufficient
                                    ? ""
                                    : " " + MIN_REQUESTS + " " + unit + "s with " + MIN_ENTITIES
                                            + " entities or more are needed to call it a pattern."),
                    measured,
                    large.size(),
                    List.of(
                            "Load only what the response needs: a projection or DTO query instead of entities.",
                            "For batch work, flush and clear the persistence context between chunks"
                                    + " (`EntityManager.clear()`)."),
                    large.stream().limit(3).map(t -> t.requestId).toList(),
                    List.of("Request", "Entities at a flush"),
                    large.stream()
                            .map(t -> List.of(t.requestId, String.valueOf(t.entities)))
                            .toList(),
                    List.of("Counts entities at a flush; a read-only session that never flushes is not measured.")));
        }
        return new Evaluation(eligible, findings);
    }
}
