package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import javax.sql.DataSource;

/**
 * Framework-neutral orchestration behind the Live Activity "Use the existing datasource" panel action,
 * mirroring {@code FlywayService}'s shape: this owns idempotency, availability and confirmation gating,
 * plus the switch itself, so both adapters report identical outcomes and messages.
 *
 * <p>Unlike Flyway (which acts against an already-configured target), this action's whole point is to
 * move a <em>running</em> instance from the in-memory default to durable persistence without a restart:
 * on success, {@link ActivitySwitchResponse#newSettings()} carries the settings the caller must start a
 * new capture poller with (see {@code ActivityCaptureFactory#start}) — the store itself was already
 * swapped by this method, but capturing new entries into it is the caller's separate responsibility,
 * since only the caller knows how to reach its own merged-feed supplier.</p>
 */
public final class ActivitySwitchService {

    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it creates a database table and starts writing to it.";

    private static final String NO_DATA_SOURCE = "No DataSource is available to reuse; configure one, or set "
            + "bootui.activity.persistence.enabled=true with a dedicated JDBC URL instead.";

    private static final String ALREADY_ACTIVE = "Live Activity is already using durable persistence.";

    /**
     * Switches {@code store} from in-memory to a durable {@link BufferedActivityStore} reusing {@code
     * dataSource}, gated by confirmation. Idempotent: if {@code store} is already persistent (including
     * a race against a concurrent call to this same method), this is a no-op that reports success rather
     * than an error.
     */
    public ActivitySwitchResponse useExistingDataSource(
            SwitchableActivityStore store,
            ActivityPersistenceSettings currentSettings,
            DataSource dataSource,
            ActivitySwitchRequest request) {
        if (store.persistent()) {
            return alreadyActive(currentSettings);
        }
        if (dataSource == null) {
            return response(404, "unavailable", NO_DATA_SOURCE, currentSettings.tableName(), null);
        }
        if (!confirmed(request)) {
            return response(400, "blocked", CONFIRMATION_REQUIRED, currentSettings.tableName(), null);
        }

        ActivityPersistenceSettings newSettings = currentSettings.withEnabledSharedMode();
        BufferedActivityStore durable;
        try {
            durable = ActivityStoreFactory.createAndVerifyDurable(newSettings, dataSource);
        } catch (ActivityStoreException ex) {
            return response(
                    500, "failed", "Failed to switch to a database: " + ex.getMessage(), newSettings.tableName(), null);
        }

        if (!store.attemptSwitchToPersistent(durable)) {
            // Lost a race against a concurrent switch attempt: some other caller already made the store
            // persistent, so this attempt's freshly built durable store is unused. Close it (rather than
            // leak its flush scheduler thread) and report the same idempotent outcome as the check above.
            durable.close();
            return alreadyActive(currentSettings);
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
                newSettings);
    }

    private ActivitySwitchResponse alreadyActive(ActivityPersistenceSettings currentSettings) {
        return response(200, "already-active", ALREADY_ACTIVE, currentSettings.tableName(), null);
    }

    private boolean confirmed(ActivitySwitchRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private ActivitySwitchResponse response(
            int status, String result, String message, String tableName, ActivityPersistenceSettings newSettings) {
        return new ActivitySwitchResponse(status, new ActivitySwitchResult(result, message, tableName), newSettings);
    }
}
