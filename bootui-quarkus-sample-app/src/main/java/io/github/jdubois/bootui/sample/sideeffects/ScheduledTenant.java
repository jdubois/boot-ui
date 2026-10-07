package io.github.jdubois.bootui.sample.sideeffects;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A scheduled run that sets its tenant and never clears it, on the scheduler's pooled worker: the {@code thread-locals}
 * sensor reports it for the run, an execution no request owns, named by {@link TenantContext#JOB}, never its value
 * ({@code docs/PLAN-v2.md} §5.16, M5-5f). Off unless {@code side-effects-seed.scheduled-every} sets its period, as the
 * agent Playwright leg does.
 */
@ApplicationScoped
public class ScheduledTenant {

    @Scheduled(every = "${side-effects-seed.scheduled-every:off}", delayed = "4s")
    void remember() {
        TenantContext.JOB.set("tenant-secret-scheduled");
    }
}
