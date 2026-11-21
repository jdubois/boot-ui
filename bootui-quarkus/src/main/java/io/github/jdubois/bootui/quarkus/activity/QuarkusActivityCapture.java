package io.github.jdubois.bootui.quarkus.activity;

import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.journal.DurableJournalCapture;
import io.github.jdubois.bootui.engine.journal.DurableJournalStore;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
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
 * <p>When enabled, {@link #onStart} starts the durable runtime-journal capture before publishing the
 * reader as active. The legacy activity store remains in-memory so there is no duplicate durable writer.
 * With the journal disabled it logs a warning and writes nothing.
 *
 * <p>Unlike Spring — whose inferred-destroy-method convention auto-closes the {@code ActivityStore} bean
 * at context shutdown — CDI/Arc has no equivalent automatic behavior, so {@link #onStop} explicitly stops
 * legacy capture, if any, and closes {@code activityStore}. Durable capture is a journal listener and is
 * closed by {@link io.github.jdubois.bootui.engine.journal.RuntimeJournal#close()} only after the journal's
 * final queue drain.</p>
 */
@ApplicationScoped
public class QuarkusActivityCapture {

    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final LiveActivityResource liveActivityResource;
    private final Instance<DurableJournalStore> durableJournalStores;
    private ActivityCapture capture;

    @Inject
    public QuarkusActivityCapture(
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            LiveActivityResource liveActivityResource,
            Instance<DurableJournalStore> durableJournalStores) {
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.liveActivityResource = liveActivityResource;
        this.durableJournalStores = durableJournalStores;
    }

    public QuarkusActivityCapture(
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            LiveActivityResource liveActivityResource) {
        this(activityStore, persistenceSettings, liveActivityResource, null);
    }

    void onStart(@Observes StartupEvent event) {
        if (!persistenceSettings.enabled()) {
            return;
        }
        if (liveActivityResource.hasDurableJournalConfiguration()) {
            try {
                DurableJournalStore store = durableJournalStores == null ? null : durableJournalStores.get();
                if (store == null) {
                    throw new IllegalStateException("No durable journal store is available");
                }
                var journal = resolveJournal();
                DurableJournalCapture durable = liveActivityResource.startDurablePersistence(journal, store);
                if (!journal.commitWhileOpen(() -> liveActivityResource.activateDurablePersistence(store, durable))) {
                    durable.close();
                }
                return;
            } catch (RuntimeException ex) {
                org.jboss.logging.Logger.getLogger(QuarkusActivityCapture.class)
                        .warn(
                                "Live Activity durable journal capture could not start; live in-memory reads remain active",
                                ex);
                return;
            }
        }
        capture = liveActivityResource.startPersistence(activityStore, persistenceSettings);
    }

    private io.github.jdubois.bootui.engine.journal.RuntimeJournal resolveJournal() {
        return liveActivityResource.runtimeJournalForCapture();
    }

    void onStop(@Observes ShutdownEvent event) {
        if (capture != null) {
            capture.close();
            capture = null;
        }
        activityStore.close();
    }
}
