package io.github.jdubois.bootui.quarkus.activity;

import io.github.jdubois.bootui.engine.activity.ActivityCaptureFactory;
import io.github.jdubois.bootui.engine.activity.ActivityCapturePoller;
import io.github.jdubois.bootui.engine.activity.ActivityForwardingSettings;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Owns the capture side of the optional Live Activity durable backends —
 * JDBC persistence ({@code bootui.activity.persistence.enabled}) or HTTP forwarding
 * ({@code bootui.activity.forwarding.enabled}) — on Quarkus, mirroring the capture wiring the Spring
 * adapter's {@code LiveActivityController} constructor performs inline.
 *
 * <p>The {@link SwitchableActivityStore}, {@link ActivityPersistenceSettings} and {@link
 * ActivityForwardingSettings} beans are always produced (see {@code BootUiEngineProducer}), so this bean
 * always starts; when both backends are disabled, both settings' {@code enabled()} are {@code false} and
 * {@link #onStart} does nothing beyond those checks — no background thread, connection or bean beyond
 * what already exists is created, exactly like the Spring adapter's equivalent branch.
 *
 * <p>When persistence is enabled, {@link #onStart} starts a capture poller (via {@link
 * ActivityCaptureFactory}) that polls {@link LiveActivityResource#mergedReport} on {@link
 * ActivityPersistenceSettings#captureInterval()}, stamping and appending whatever it has not yet captured
 * into the shared store. When persistence is disabled but forwarding is enabled instead, the identical
 * poller mechanics are started via {@link ActivityCaptureFactory}'s primitive-typed overload, fed with
 * {@link ActivityForwardingSettings}'s own {@code instanceId}/{@code bufferMaxEntries}/{@code
 * captureInterval} instead — without this branch, an instance configured purely as an HTTP-forwarding
 * sender would build a working {@code HttpActivityStore} that never receives anything to forward, since
 * the capture poller (not the store) is what reads the merged feed and feeds new entries in. Either way,
 * reusing the resource's own merged feed (rather than re-reading the four signal sources independently)
 * means self-filtering, masking and bounds are inherited identically to what the panel itself renders.
 *
 * <p>Unlike Spring — whose inferred-destroy-method convention auto-closes the {@code ActivityStore} bean
 * at context shutdown — CDI/Arc has no equivalent automatic behavior, so {@link #onStop} explicitly stops
 * the poller (making one last synchronous capture pass first, so entries produced since the last tick
 * aren't dropped) and then closes {@code activityStore} itself (flushing any still-buffered entries,
 * bounded, so shutdown is never blocked indefinitely — see {@code BufferedActivityStore#close()}). This is
 * independent of {@link LiveActivityResource#onStop}, which only ever stops a poller started by the
 * runtime "Use the existing datasource" switch — the two poller fields are never both live at once, since
 * that switch only succeeds when the store was not already persistent.</p>
 */
@ApplicationScoped
public class QuarkusActivityCapture {

    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final ActivityForwardingSettings forwardingSettings;
    private final LiveActivityResource liveActivityResource;
    private ActivityCapturePoller poller;

    @Inject
    public QuarkusActivityCapture(
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            ActivityForwardingSettings forwardingSettings,
            LiveActivityResource liveActivityResource) {
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.forwardingSettings = forwardingSettings;
        this.liveActivityResource = liveActivityResource;
    }

    void onStart(@Observes StartupEvent event) {
        if (persistenceSettings.enabled()) {
            poller = ActivityCaptureFactory.start(
                    activityStore,
                    persistenceSettings,
                    () -> liveActivityResource.mergedReport(0).entries());
        } else if (forwardingSettings.enabled()) {
            poller = ActivityCaptureFactory.start(
                    activityStore,
                    forwardingSettings.instanceId(),
                    forwardingSettings.bufferMaxEntries(),
                    forwardingSettings.captureInterval(),
                    () -> liveActivityResource.mergedReport(0).entries());
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        if (poller != null) {
            poller.close();
            poller = null;
        }
        activityStore.close();
    }
}
