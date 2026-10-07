package io.github.jdubois.bootui.sample.sideeffects;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.SuccessfulExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * A scheduled run that sets its tenant and never clears it, on the scheduler's pooled worker: the {@code thread-locals}
 * sensor reports it for the run, an execution no request owns, named by {@link TenantContext#JOB}, never its value
 * ({@code docs/PLAN-v2.md} §5.16, M5-5f). Off unless {@code side-effects-seed.scheduled-every} sets its period, as the
 * agent Playwright leg does.
 *
 * <p>A thread local already set when a run's scope opens is never reported again, so the scheduler's success event,
 * fired on the same thread once the run and its scope ended, removes it: every run leaves it set anew, and the row
 * comes back after a <b>Clear recording</b>.</p>
 */
@ApplicationScoped
public class ScheduledTenant {

    static final String METHOD = ScheduledTenant.class.getName() + "#remember";

    @Scheduled(every = "${side-effects-seed.scheduled-every:off}", delayed = "4s")
    void remember() {
        TenantContext.JOB.set("tenant-secret-scheduled");
    }

    void forget(@Observes SuccessfulExecution event) {
        if (METHOD.equals(event.getExecution().getTrigger().getMethodDescription())) {
            TenantContext.JOB.remove();
        }
    }
}
