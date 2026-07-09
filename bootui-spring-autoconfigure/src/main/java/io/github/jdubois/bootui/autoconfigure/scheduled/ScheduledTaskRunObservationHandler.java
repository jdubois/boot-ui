package io.github.jdubois.bootui.autoconfigure.scheduled;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.lang.reflect.Method;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Feeds {@link ScheduledTaskRunStore} from Spring Framework's own {@code @Scheduled} method
 * instrumentation, so BootUI needs no AOP proxying or bean wrapping to capture task executions.
 *
 * <p>Since Spring Framework 6.1, every {@code @Scheduled} method invocation ({@code
 * ScheduledMethodRunnable}) always wraps its call in a Micrometer {@link Observation} carrying a
 * {@link ScheduledTaskObservationContext} (target class + method, completion flag, and — via {@link
 * Observation.Context#getError()} — the thrown exception, if any) against whichever {@code
 * ObservationRegistry} the {@code ScheduledTaskRegistrar} has been given. Spring Boot does not wire a
 * registry there itself (there is no {@code spring.scheduled.*} observability auto-configuration as of
 * this writing), so {@link BootUiSchedulingConfigurer} installs one carrying this handler. Registering
 * this handler is therefore the entire capture mechanism: no bean post-processing, no proxying, and no
 * dependency on whether the host application also observes scheduling for its own metrics/tracing (see
 * {@link BootUiSchedulingConfigurer} for how the two coexist).</p>
 *
 * <p>Only {@code @Scheduled} <em>method</em> tasks go through this observation; a manually registered
 * {@code Runnable}/{@code Trigger} task (via {@code SchedulingConfigurer}) is not observed and so does
 * not appear as a {@code SCHEDULED_TASK} activity entry — consistent with the static Scheduled Tasks
 * panel, which lists it as a task but with a generic runnable name.</p>
 */
public final class ScheduledTaskRunObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private final ScheduledTaskRunStore store;
    private final BootUiSelfDataFilter selfDataFilter;

    public ScheduledTaskRunObservationHandler(ScheduledTaskRunStore store, BootUiSelfDataFilter selfDataFilter) {
        this.store = store;
        this.selfDataFilter = selfDataFilter;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        context.put(
                StartMarker.class,
                new StartMarker(
                        System.currentTimeMillis(), Thread.currentThread().getName()));
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        StartMarker start = context.get(StartMarker.class);
        String runnable = runnableName(context);
        if (!selfDataFilter.shouldIncludeScheduledTask(runnable)) {
            return;
        }
        long startTimestamp = start == null ? System.currentTimeMillis() : start.startTimestamp();
        long durationMs = Math.max(0L, System.currentTimeMillis() - startTimestamp);
        String thread = start == null ? Thread.currentThread().getName() : start.thread();
        Throwable error = context.getError();
        if (error != null) {
            store.record(
                    runnable,
                    startTimestamp,
                    durationMs,
                    false,
                    error.getClass().getName(),
                    error.getMessage(),
                    thread);
        } else {
            store.record(runnable, startTimestamp, durationMs, context.isComplete(), null, null, thread);
        }
    }

    /**
     * Matches the identifier {@link io.github.jdubois.bootui.autoconfigure.scheduled.SpringScheduledTaskProvider}
     * derives for the same task (declaring class + method name), so a captured run and the static
     * definition are recognizably the same task in the UI.
     */
    private static String runnableName(ScheduledTaskObservationContext context) {
        Method method = context.getMethod();
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    private record StartMarker(long startTimestamp, String thread) {}
}
