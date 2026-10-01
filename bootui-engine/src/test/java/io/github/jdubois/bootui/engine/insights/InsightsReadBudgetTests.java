package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * §5.4's read budget ({@code docs/PLAN-v2.md}): projecting a full journal of 50,000 retained events and evaluating every
 * observation stays within 250 ms on a reference machine. Pinned here with a generous margin, so a slow CI runner never
 * fails it while a quadratic regression still does.
 */
class InsightsReadBudgetTests {

    @Test
    void aFullJournalIsProjectedAndEvaluatedWellWithinTheReadBudget() {
        List<JournalEntry> entries = new ArrayList<>(50_000);
        long sequence = 0;
        for (int request = 0; entries.size() < 50_000; request++) {
            CorrelationContext context = CorrelationContext.forRequest("r" + request);
            String route = "/api/route" + (request % 200) + "/{id}";
            for (int sql = 0; sql < 7; sql++) {
                entries.add(entry(
                        ++sequence,
                        RuntimeEvent.of(
                                JournalSource.SQL,
                                1_000,
                                1_000_000,
                                context,
                                "t",
                                null,
                                false,
                                new SqlPayload("select * from t" + (sql % 3) + " where id = ?", null, "db", false))));
            }
            entries.add(entry(
                    ++sequence,
                    RuntimeEvent.of(
                            JournalSource.CONNECTION,
                            1_000,
                            5_000_000,
                            context,
                            "t",
                            null,
                            false,
                            new ConnectionPayload("db", 0, 7, sequence))));
            entries.add(entry(
                    ++sequence,
                    RuntimeEvent.of(
                            JournalSource.HTTP,
                            1_000,
                            9_000_000,
                            context,
                            "t",
                            null,
                            false,
                            new HttpPayload("GET", route.replace("{id}", "1"), route, null, 200))));
        }
        JournalStatus status = new JournalStatus(
                true,
                "i",
                "run",
                sequence,
                entries.size(),
                0,
                0,
                0,
                50_000,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                Map.of(),
                Map.of(),
                0);

        long best = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            InsightsSnapshot snapshot =
                    InsightsSnapshot.of(entries, status, RouteTemplateResolver.empty(), source -> true, source -> true);
            for (Observation observation : RuntimeInsightsService.defaultObservations()) {
                observation.evaluate(snapshot);
            }
            best = Math.min(best, System.nanoTime() - start);
        }

        assertThat(best / 1_000_000).as("milliseconds, against a 250 ms budget").isLessThan(2_000);
    }

    private static JournalEntry entry(long sequence, RuntimeEvent event) {
        return new JournalEntry(sequence, event, event.estimatedBytes());
    }
}
