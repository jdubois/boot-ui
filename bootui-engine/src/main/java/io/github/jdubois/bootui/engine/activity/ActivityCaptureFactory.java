package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds and starts the sequencer/coordinator/poller trio behind the Live Activity capture side, so
 * every place that needs to start capturing — an adapter's startup wiring (when persistence or
 * forwarding is enabled from the start) and the "Use the existing datasource" runtime switch (see
 * {@code ActivitySwitchService}, which enables persistence mid-process) — shares one implementation
 * instead of duplicating this construction across Spring's controller constructor, Quarkus's
 * {@code QuarkusActivityCapture}, and the switch action on both adapters.
 */
public final class ActivityCaptureFactory {

    private ActivityCaptureFactory() {}

    /**
     * Builds a fresh {@link ActivitySequencer} + {@link ActivityCaptureCoordinator} over {@code store}
     * and {@code settings}, then starts an {@link ActivityCapturePoller} polling {@code feed} on {@code
     * settings.captureInterval()}. The returned poller is already running; the caller owns closing it
     * (typically registering {@link ActivityCapturePoller#close()} into whatever shutdown-hook mechanism
     * the adapter uses).
     *
     * <p>Delegates to the primitive-typed {@link #start(ActivityStore, String, int, Duration, Supplier)}
     * overload, which contains the actual logic; kept as a thin, unchanged entry point since every
     * existing JDBC-persistence call site already has an {@link ActivityPersistenceSettings} in hand.
     */
    public static ActivityCapturePoller start(
            ActivityStore store, ActivityPersistenceSettings settings, Supplier<List<ActivityEntryDto>> feed) {
        return start(store, settings.instanceId(), settings.bufferMaxEntries(), settings.captureInterval(), feed);
    }

    /**
     * The primitive-typed twin of {@link #start(ActivityStore, ActivityPersistenceSettings, Supplier)},
     * taking the handful of fields the capture side actually needs directly rather than a whole {@link
     * ActivityPersistenceSettings}. Capturing into a store is orthogonal to which durable backend that
     * store happens to write through: an HTTP-forwarding sender (configured via the separate, additive
     * {@code ActivityForwardingSettings}) needs to start exactly the same sequencer/coordinator/poller
     * trio as a JDBC-persistence instance does, but has no {@link ActivityPersistenceSettings} of its own
     * to pass in. This overload lets both adapters' capture-start sites branch on whichever settings
     * record is actually enabled and still call into one shared implementation.
     */
    public static ActivityCapturePoller start(
            ActivityStore store,
            String instanceId,
            int bufferMaxEntries,
            Duration captureInterval,
            Supplier<List<ActivityEntryDto>> feed) {
        ActivitySequencer sequencer = new ActivitySequencer(instanceId);
        ActivityCaptureCoordinator coordinator = new ActivityCaptureCoordinator(store, sequencer, bufferMaxEntries);
        ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, feed);
        poller.start(captureInterval);
        return poller;
    }
}
