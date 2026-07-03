package io.github.jdubois.bootui.quarkus.activity;

import io.github.jdubois.bootui.engine.activity.ActivityCaptureCoordinator;
import io.github.jdubois.bootui.engine.activity.ActivityCapturePoller;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivitySequencer;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Owns the capture side of the optional Live Activity JDBC persistence backend
 * ({@code bootui.activity.persistence.enabled}) on Quarkus, mirroring the capture wiring the Spring
 * adapter's {@code LiveActivityController} constructor performs inline.
 *
 * <p>The {@link ActivityStore} and {@link ActivityPersistenceSettings} beans are always produced (see
 * {@code BootUiEngineProducer}), so this bean always starts; when persistence is disabled the settings'
 * {@code enabled()} is {@code false} and {@link #onStart} does nothing beyond that check — no background
 * thread, connection or bean beyond what already exists is created, exactly like the Spring adapter's
 * {@code @ConditionalOnProperty}-gated configuration.
 *
 * <p>When enabled, {@link #onStart} polls {@link LiveActivityResource#mergedReport} on
 * {@link ActivityPersistenceSettings#captureInterval()}, stamping and appending whatever it has not yet
 * captured into the shared store (see {@link ActivityCaptureCoordinator}). Reusing the resource's own
 * merged feed (rather than re-reading the four signal sources independently) means self-filtering,
 * masking and bounds are inherited identically to what the panel itself renders.
 *
 * <p>Unlike Spring — whose inferred-destroy-method convention auto-closes the {@code ActivityStore} bean
 * at context shutdown — CDI/Arc has no equivalent automatic behavior, so {@link #onStop} explicitly stops
 * the poller (making one last synchronous capture pass first, so entries produced since the last tick
 * aren't dropped) and then closes {@code activityStore} itself (flushing any still-buffered entries,
 * bounded, so shutdown is never blocked indefinitely — see {@code BufferedActivityStore#close()}).</p>
 */
@ApplicationScoped
public class QuarkusActivityCapture {

    private final ActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final LiveActivityResource liveActivityResource;
    private ActivityCapturePoller poller;

    @Inject
    public QuarkusActivityCapture(
            ActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            LiveActivityResource liveActivityResource) {
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.liveActivityResource = liveActivityResource;
    }

    void onStart(@Observes StartupEvent event) {
        if (!persistenceSettings.enabled()) {
            return;
        }
        ActivitySequencer sequencer = new ActivitySequencer(persistenceSettings.instanceId());
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(activityStore, sequencer, persistenceSettings.bufferMaxEntries());
        poller = new ActivityCapturePoller(
                coordinator, () -> liveActivityResource.mergedReport(0).entries());
        poller.start(persistenceSettings.captureInterval());
    }

    void onStop(@Observes ShutdownEvent event) {
        if (poller != null) {
            poller.close();
            poller = null;
        }
        activityStore.close();
    }
}
