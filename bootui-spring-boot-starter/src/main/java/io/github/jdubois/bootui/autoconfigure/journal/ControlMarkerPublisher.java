package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.engine.journal.ControlMarkers;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Marks the run's availability changes, configuration refreshes, and shutdown in the runtime journal ({@code
 * docs/PLAN-v2.md} §5.18, M4-7), so a discontinuity in a run is explained. Availability is marked once the application
 * is ready, and only when it moves away from, or back to, a ready application's state. A refresh is Spring Cloud's
 * {@code EnvironmentChangeEvent}, read by name so Spring Cloud stays optional, and names the keys that changed, never
 * their values.
 */
public final class ControlMarkerPublisher implements ApplicationListener<ApplicationEvent> {

    static final String ENVIRONMENT_CHANGE_EVENT =
            "org.springframework.cloud.context.environment.EnvironmentChangeEvent";

    private final RuntimeJournal journal;
    private final ApplicationContext context;
    private final Map<Class<?>, AvailabilityState> states = new HashMap<>(
            Map.of(LivenessState.class, LivenessState.CORRECT, ReadinessState.class, ReadinessState.ACCEPTING_TRAFFIC));
    private volatile boolean ready;

    public ControlMarkerPublisher(RuntimeJournal journal, ApplicationContext context) {
        this.journal = journal;
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent readyEvent) {
            ready |= readyEvent.getApplicationContext() == context;
        } else if (event instanceof AvailabilityChangeEvent<?> change) {
            availability(change.getState());
        } else if (event instanceof ContextClosedEvent closed) {
            if (closed.getApplicationContext() == context) {
                ControlMarkers.shutdown(journal);
            }
        } else if (ENVIRONMENT_CHANGE_EVENT.equals(event.getClass().getName())) {
            ControlMarkers.configRefresh(journal, keys(event));
        }
    }

    private synchronized void availability(AvailabilityState state) {
        if (!ready || state == null) {
            return;
        }
        AvailabilityState previous = states.put(state.getClass(), state);
        if (state.equals(previous)) {
            return;
        }
        String type = state instanceof LivenessState
                ? "Liveness"
                : state instanceof ReadinessState
                        ? "Readiness"
                        : state.getClass().getSimpleName();
        ControlMarkers.availability(journal, type + " " + state);
    }

    private static Collection<String> keys(ApplicationEvent event) {
        try {
            Method getKeys = event.getClass().getMethod("getKeys");
            if (getKeys.invoke(event) instanceof Set<?> keys) {
                return keys.stream().map(String::valueOf).toList();
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // A refresh whose keys cannot be read is still marked.
        }
        return List.of();
    }
}
