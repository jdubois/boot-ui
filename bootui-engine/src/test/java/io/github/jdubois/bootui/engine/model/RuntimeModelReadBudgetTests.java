package io.github.jdubois.bootui.engine.model;

import static io.github.jdubois.bootui.engine.model.JournalFixture.child;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import org.junit.jupiter.api.Test;

/**
 * §5.4's read budget ({@code docs/PLAN-v2.md}): projecting the runtime model from a full journal of 50,000 retained
 * events stays within 250 ms on a reference machine. Pinned with a generous margin, so a slow CI runner never fails it
 * while a quadratic regression still does.
 */
class RuntimeModelReadBudgetTests {

    @Test
    void aFullJournalIsProjectedWellWithinTheReadBudget() {
        JournalFixture journal = new JournalFixture();
        for (int request = 0; journal.entries().size() < 50_000; request++) {
            journal.request(
                    "GET",
                    "/api/route" + (request % 200) + "/{id}",
                    child(
                            JournalSource.SQL,
                            new SqlPayload("select * from t" + (request % 40) + " where id = ?", null, "db", false)),
                    child(JournalSource.SQL, new SqlPayload("update audit set n = n + 1", null, "db", false)),
                    child(JournalSource.CACHE, new CachePayload("c" + (request % 10), "HIT", null)),
                    child(
                            JournalSource.REST_CLIENT,
                            new RestClientPayload("GET", "h" + (request % 20) + ":80", "/", 200, "RestClient", false)));
        }

        long best = Long.MAX_VALUE;
        RuntimeModel model = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            model = RuntimeModelProjection.project(
                    journal.entries(),
                    RouteTemplateResolver.empty(),
                    PocFixture.structure(),
                    0,
                    System::nanoTime,
                    Long.MAX_VALUE);
            best = Math.min(best, System.nanoTime() - start);
        }

        assertThat(best / 1_000_000).as("milliseconds, against a 250 ms budget").isLessThan(2_000);
        assertThat(model.partial()).isFalse();
        assertThat(model.edges()).isNotEmpty();
    }
}
