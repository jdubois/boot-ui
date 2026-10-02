package io.github.jdubois.bootui.quarkus.activity;

import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
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
 * <p>The {@link SwitchableActivityStore} and {@link ActivityPersistenceSettings} beans are always
 * produced (see {@code BootUiEngineProducer}), so this bean always starts; when persistence is disabled
 * the settings' {@code enabled()} is {@code false} and {@link #onStart} does nothing beyond that check —
 * no background thread, connection or bean beyond what already exists is created, exactly like the Spring
 * adapter's {@code @ConditionalOnProperty}-gated configuration.
 *
 * <p>When enabled, {@link #onStart} starts the runtime journal's subscriber (via {@link
 * LiveActivityResource#startPersistence}), which renders each recorded batch as the journal's feed renders it and
 * appends it to the shared store; with the journal disabled it logs a warning and writes nothing.
 *
 * <p>Unlike Spring — whose inferred-destroy-method convention auto-closes the {@code ActivityStore} bean
 * at context shutdown — CDI/Arc has no equivalent automatic behavior, so {@link #onStop} explicitly stops
 * the capture (processing what the journal already recorded first, so entries produced since the last tick
 * aren't dropped) and then closes {@code activityStore} itself (flushing any still-buffered entries,
 * bounded, so shutdown is never blocked indefinitely — see {@code BufferedActivityStore#close()}). This is
 * independent of {@link LiveActivityResource#onStop}, which only ever stops a capture started by the
 * runtime "Use the existing datasource" switch — the two capture fields are never both live at once, since
 * that switch only succeeds when the store was not already persistent.</p>
 */
@ApplicationScoped
public class QuarkusActivityCapture {

    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final LiveActivityResource liveActivityResource;
    private ActivityCapture capture;

    @Inject
    public QuarkusActivityCapture(
            SwitchableActivityStore activityStore,
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
        capture = liveActivityResource.startPersistence(activityStore, persistenceSettings);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (capture != null) {
            capture.close();
            capture = null;
        }
        activityStore.close();
    }
}
