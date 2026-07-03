package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds and starts the sequencer/coordinator/poller trio behind the Live Activity capture side, so
 * every place that needs to start capturing — an adapter's startup wiring (when persistence is enabled
 * from the start) and the "Use the existing datasource" runtime switch (see
 * {@code ActivitySwitchService}, which enables persistence mid-process) — shares one implementation
 * instead of duplicating this construction three times over (Spring's controller constructor, Quarkus's
 * {@code QuarkusActivityCapture}, and the switch action on both adapters).
 */
public final class ActivityCaptureFactory {

    private ActivityCaptureFactory() {}

    /**
     * Builds a fresh {@link ActivitySequencer} + {@link ActivityCaptureCoordinator} over {@code store}
     * and {@code settings}, then starts an {@link ActivityCapturePoller} polling {@code feed} on {@code
     * settings.captureInterval()}. The returned poller is already running; the caller owns closing it
     * (typically registering {@link ActivityCapturePoller#close()} into whatever shutdown-hook mechanism
     * the adapter uses).
     */
    public static ActivityCapturePoller start(
            ActivityStore store, ActivityPersistenceSettings settings, Supplier<List<ActivityEntryDto>> feed) {
        ActivitySequencer sequencer = new ActivitySequencer(settings.instanceId());
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, sequencer, settings.bufferMaxEntries());
        ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, feed);
        poller.start(settings.captureInterval());
        return poller;
    }
}
