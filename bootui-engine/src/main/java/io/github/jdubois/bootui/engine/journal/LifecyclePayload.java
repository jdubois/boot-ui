package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * A run's lifecycle ({@code docs/PLAN-v2.md} §5.18). The application publishes one {@link #RUN_STARTED} event when it
 * is ready, with its {@link RunStart}: the time to ready, the slowest startup steps, and the comparability facts. Control
 * and availability markers ({@link ControlMarkers}) follow: BootUI's own actions, availability changes, configuration
 * refreshes, and shutdown. It holds names and shapes only, never a property value or a principal.
 *
 * @param kind what happened, such as {@link #RUN_STARTED}
 * @param target what it happened to, such as a logger, cache, or property name, never a value; {@code null} for a run
 * @param runStart what the run recorded when it started, for {@link #RUN_STARTED}
 */
public record LifecyclePayload(String kind, String target, RunStart runStart) implements RuntimeEventPayload {

    /** The run is ready. */
    public static final String RUN_STARTED = "RUN_STARTED";

    /** BootUI changed the application from one of its panels, such as a logger level or a cache clear (M4-7). */
    public static final String ACTION = "ACTION";

    /** The application's liveness or readiness changed. */
    public static final String AVAILABILITY = "AVAILABILITY";

    /** The application's configuration was refreshed, naming the keys that changed. */
    public static final String CONFIG_REFRESH = "CONFIG_REFRESH";

    /** The application is shutting down. */
    public static final String SHUTDOWN = "SHUTDOWN";

    /** Whether this is a control or availability marker rather than the run's start. */
    public boolean marker() {
        return !RUN_STARTED.equals(kind);
    }

    public LifecyclePayload {
        Objects.requireNonNull(kind, "kind");
    }

    /** The {@link #RUN_STARTED} payload of {@code runStart}. */
    public static LifecyclePayload runStarted(RunStart runStart) {
        return new LifecyclePayload(RUN_STARTED, null, Objects.requireNonNull(runStart, "runStart"));
    }

    @Override
    public int estimatedBytes() {
        int bytes = 48 + RuntimeEvent.stringBytes(kind) + RuntimeEvent.stringBytes(target);
        if (runStart != null) {
            bytes += 48;
            for (StartupStepTiming step : runStart.slowestSteps()) {
                bytes += 40 + RuntimeEvent.stringBytes(step.name()) + RuntimeEvent.stringBytes(step.bean());
            }
            bytes += runStart.facts().estimatedBytes();
        }
        return bytes;
    }
}
