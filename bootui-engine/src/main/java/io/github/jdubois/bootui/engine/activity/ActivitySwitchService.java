package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.sql.DataSource;

/**
 * Framework-neutral orchestration behind the Live Activity "Use the existing datasource" panel action,
 * mirroring {@code FlywayService}'s shape: this owns idempotency, availability and confirmation gating,
 * plus the switch itself, so both adapters report identical outcomes and messages.
 *
 * <p>A runtime switch requires an enabled journal and a running capture before publishing its durable
 * store. The adapter supplies native capture wiring and owns the returned capture's shutdown. Configured
 * startup capture can still defer until journal injection; a user action cannot.</p>
 */
public final class ActivitySwitchService {

    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it creates a database table and starts writing to it.";

    private static final String NO_DATA_SOURCE = "No DataSource is available to reuse; configure one, or set "
            + "bootui.activity.persistence.enabled=true with a dedicated JDBC URL instead.";

    private static final String ALREADY_ACTIVE = "Live Activity is already using durable persistence.";

    private static final String JOURNAL_UNAVAILABLE = "Live Activity cannot start durable history because the runtime"
            + " journal is unavailable, disabled (bootui.runtime-journal.enabled=false), or closed. No database table was"
            + " created and in-memory storage remains active.";

    private static final System.Logger LOG = System.getLogger(ActivitySwitchService.class.getName());

    /**
     * Switches {@code store} from in-memory to a durable {@link BufferedActivityStore} reusing {@code
     * dataSource}, gated by confirmation. Idempotent: if {@code store} is already persistent (including
     * a race against a concurrent call to this same method), this is a no-op that reports success rather
     * than an error.
     *
     * @param journal the available capture source; a runtime action cannot defer until injection
     * @param startCapture starts a subscriber writing to the supplied candidate store, not {@code store};
     *     returns its running handle, cleaning up any partial registration itself if it throws
     */
    public ActivitySwitchResponse useExistingDataSource(
            SwitchableActivityStore store,
            ActivityPersistenceSettings currentSettings,
            DataSource dataSource,
            ActivitySwitchRequest request,
            RuntimeJournal journal,
            BiFunction<ActivityStore, ActivityPersistenceSettings, ActivityCapture> startCapture) {
        synchronized (store) {
            return switchWithCapture(store, currentSettings, dataSource, request, journal, startCapture);
        }
    }

    private ActivitySwitchResponse switchWithCapture(
            SwitchableActivityStore store,
            ActivityPersistenceSettings currentSettings,
            DataSource dataSource,
            ActivitySwitchRequest request,
            RuntimeJournal journal,
            BiFunction<ActivityStore, ActivityPersistenceSettings, ActivityCapture> startCapture) {
        if (store.persistent()) {
            return alreadyActive(currentSettings);
        }
        if (dataSource == null) {
            return response(404, "unavailable", NO_DATA_SOURCE, currentSettings.tableName(), null);
        }
        if (!confirmed(request)) {
            return response(400, "blocked", CONFIRMATION_REQUIRED, currentSettings.tableName(), null);
        }
        if (journal == null || !journal.isOpen()) {
            return response(409, "unavailable", JOURNAL_UNAVAILABLE, currentSettings.tableName(), null);
        }

        ActivityPersistenceSettings newSettings = currentSettings.withEnabledSharedMode();
        BufferedActivityStore durable;
        try {
            durable = ActivityStoreFactory.createAndVerifyDurable(newSettings, dataSource);
        } catch (ActivityStoreException ex) {
            return response(
                    500, "failed", "Failed to switch to a database: " + ex.getMessage(), newSettings.tableName(), null);
        }

        ActivityCapture capture;
        try {
            // The subscriber writes directly to the candidate, including batches dispatched before publication.
            capture = Objects.requireNonNull(startCapture.apply(durable, newSettings), "capture did not start");
        } catch (RuntimeException ex) {
            durable.close();
            LOG.log(System.Logger.Level.WARNING, "Failed to start Live Activity durable capture", ex);
            return response(
                    500,
                    "failed",
                    "Failed to start Live Activity capture. The database table may already have been created,"
                            + " but in-memory storage remains active.",
                    newSettings.tableName(),
                    null);
        }
        boolean installed = false;
        try {
            installed = journal.commitWhileOpen(() -> store.attemptSwitchToPersistent(durable));
            if (!installed) {
                return alreadyActive(currentSettings);
            }
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Failed to publish Live Activity durable capture", ex);
            return response(
                    500,
                    "failed",
                    "Failed to publish Live Activity capture; the runtime journal must remain open until publication."
                            + " The database table may already have been created, but in-memory storage remains active.",
                    newSettings.tableName(),
                    null);
        } finally {
            if (!installed) {
                try {
                    capture.close();
                } finally {
                    durable.close();
                }
            }
        }
        return new ActivitySwitchResponse(
                200,
                new ActivitySwitchResult(
                        "success",
                        "Live Activity is now saving to the \"" + newSettings.tableName() + "\" table. This switch"
                                + " applies to this running instance only: it is not written to configuration, so a"
                                + " restart reverts to in-memory storage unless you also set"
                                + " bootui.activity.persistence.enabled=true.",
                        newSettings.tableName()),
                newSettings,
                capture);
    }

    private ActivitySwitchResponse alreadyActive(ActivityPersistenceSettings currentSettings) {
        return response(200, "already-active", ALREADY_ACTIVE, currentSettings.tableName(), null);
    }

    private boolean confirmed(ActivitySwitchRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private ActivitySwitchResponse response(
            int status, String result, String message, String tableName, ActivityPersistenceSettings newSettings) {
        return new ActivitySwitchResponse(
                status, new ActivitySwitchResult(result, message, tableName), newSettings, null);
    }
}
