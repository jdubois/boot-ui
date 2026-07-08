package io.github.jdubois.bootui.quarkus.scheduled;

import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.ScheduledExecution;
import io.quarkus.scheduler.SuccessfulExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Feeds the shared engine {@link ScheduledTaskRunStore} from Quarkus's own {@code @Scheduled} method
 * instrumentation, so BootUI needs neither method interception nor a competing {@code JobInstrumenter}
 * (the scheduler allows only one CDI bean of that type, and {@code quarkus-opentelemetry} already claims
 * it when scheduler tracing is enabled — see {@code docs/PLAN.md} §3.4).
 *
 * <p>Quarkus's scheduler ({@code BaseScheduler}, shared by the built-in {@code SimpleScheduler} and the
 * Quartz extension) always fires an ordinary CDI {@link SuccessfulExecution} or {@link FailedExecution}
 * event after every {@code @Scheduled} invocation, regardless of how many other observers exist. Any
 * number of {@code @Observes} beans may listen, so this observer coexists with
 * {@code quarkus-opentelemetry}'s own consumers without conflict.</p>
 *
 * <p>These events fire only on completion — there is no official start hook — so {@link
 * ScheduledExecution#getFireTime()} (the trigger's fire time) is used as a reasonable proxy for the run's
 * start timestamp, accepting a small margin of error from invoker-chain overhead; this is acceptable for
 * the Live Activity duration display, not precise profiling.</p>
 *
 * <p>Only annotation-discovered {@code @Scheduled} methods carry a method description ({@link
 * io.quarkus.scheduler.Trigger#getMethodDescription()} returns {@code declaringClassName#methodName},
 * matching the identifier {@link QuarkusScheduledTaskProvider} derives for the same task); a
 * programmatically registered job has no method description and is skipped, mirroring the static
 * Scheduled Tasks panel's documented scope. BootUI's own scheduled methods are filtered out via the
 * shared engine {@link InternalPackageMatcher}, matching the class-name portion of the description.</p>
 */
@ApplicationScoped
public class QuarkusScheduledTaskRunRecorder {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.quarkus", "io.github.jdubois.bootui.core"));

    private final ScheduledTaskRunStore store;

    @Inject
    public QuarkusScheduledTaskRunRecorder(ScheduledTaskRunStore store) {
        this.store = store;
    }

    void onSuccess(@Observes SuccessfulExecution event) {
        record(event.getExecution(), true, null);
    }

    void onFailure(@Observes FailedExecution event) {
        record(event.getExecution(), false, event.getException());
    }

    private void record(ScheduledExecution execution, boolean success, Throwable failure) {
        String runnable = execution.getTrigger().getMethodDescription();
        if (runnable == null || runnable.isBlank()) {
            return; // programmatically registered job: no stable method identity to report
        }
        String declaringClass = declaringClassName(runnable);
        if (INTERNAL_PACKAGES.matchesName(declaringClass)) {
            return;
        }
        long startTimestamp = execution.getFireTime().toEpochMilli();
        long durationMs = Math.max(0L, System.currentTimeMillis() - startTimestamp);
        String thread = Thread.currentThread().getName();
        if (failure != null) {
            store.record(
                    runnable,
                    startTimestamp,
                    durationMs,
                    false,
                    failure.getClass().getName(),
                    failure.getMessage(),
                    thread);
        } else {
            store.record(runnable, startTimestamp, durationMs, success, null, null, thread);
        }
    }

    private static String declaringClassName(String methodDescription) {
        int separator = methodDescription.indexOf('#');
        return separator < 0 ? methodDescription : methodDescription.substring(0, separator);
    }
}
