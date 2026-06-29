package com.example.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A CDI bean carrying {@code @Scheduled} methods, exercised by the scheduler integration test.
 *
 * <p>It lives under {@code com.example.scheduler} (a realistic host-application package), deliberately
 * <em>not</em> under {@code io.github.jdubois.bootui.*}, so the engine self-filter — which hides BootUI's own
 * scheduled methods from the panel — does not drop these tasks.</p>
 *
 * <p>The methods are no-ops with intervals far enough out that they do not fire during the test; what matters
 * is that the deployment processor's build-time Jandex scan discovers their {@code @Scheduled} metadata and
 * records it, so {@code GET /bootui/api/scheduled} can report them. The set covers a cron trigger, a fixed-rate
 * ({@code every}) trigger, and a fixed-rate trigger with a string {@code delayed} initial delay — the three
 * shapes {@code QuarkusScheduledTaskProvider} maps.</p>
 */
@ApplicationScoped
public class SampleScheduledJobs {

    @Scheduled(cron = "0 0 0 * * ?")
    void cronJob() {
        // no-op: discovered at build time, asserted via the panel, never fires during the test
    }

    @Scheduled(every = "30s")
    void everyJob() {
        // no-op
    }

    @Scheduled(every = "1h", delayed = "10s")
    void delayedEveryJob() {
        // no-op
    }
}
