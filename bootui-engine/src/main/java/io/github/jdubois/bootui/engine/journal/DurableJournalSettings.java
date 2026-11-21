package io.github.jdubois.bootui.engine.journal;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static startup settings for durable runtime-journal persistence.
 *
 * <p>This record deliberately contains no datasource: adapters bind datasource selection separately.
 *
 * @param namespaceId opaque namespace isolating one local installation or explicitly configured tenant
 * @param applicationId application key within the namespace
 * @param runId unique 128-bit id of this application run
 * @param processId operating-system process id
 * @param instanceId existing BootUI class-loader instance id
 * @param enabled whether durable runtime-journal capture should be started
 * @param flushInterval maximum delay between pending-buffer flushes
 * @param maxPendingEntries maximum entries awaiting persistence
 * @param maxPendingBytes maximum bytes awaiting persistence
 * @param hotMaxEntries maximum recent entries retained in memory for fast reads
 * @param tablePrefix prefix from which the durable journal tables are named
 * @param retention age after which durable runs and entries may be pruned
 * @param readerArchiveCrossNamespace whether archive readers may explicitly query other namespaces
 */
public record DurableJournalSettings(
        String namespaceId,
        String applicationId,
        String runId,
        String processId,
        String instanceId,
        boolean enabled,
        Duration flushInterval,
        int maxPendingEntries,
        long maxPendingBytes,
        int hotMaxEntries,
        String tablePrefix,
        Duration retention,
        boolean readerArchiveCrossNamespace) {

    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_PENDING_ENTRIES = 500;
    public static final long DEFAULT_MAX_PENDING_BYTES = 32L * 1024 * 1024;
    public static final int DEFAULT_HOT_MAX_ENTRIES = 500;
    public static final String DEFAULT_TABLE_PREFIX = "bootui_activity";
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,39}");

    public DurableJournalSettings {
        DurableJournalIdentity identity =
                new DurableJournalIdentity(namespaceId, applicationId, runId, processId, instanceId);
        namespaceId = identity.namespaceId();
        applicationId = identity.applicationId();
        runId = identity.runId();
        processId = identity.processId();
        instanceId = identity.instanceId();
        flushInterval = positive("flushInterval", flushInterval);
        maxPendingEntries = positive("maxPendingEntries", maxPendingEntries);
        maxPendingBytes = positive("maxPendingBytes", maxPendingBytes);
        hotMaxEntries = positive("hotMaxEntries", hotMaxEntries);
        tablePrefix = Objects.requireNonNull(tablePrefix, "tablePrefix must not be null")
                .strip();
        if (!SQL_IDENTIFIER.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("tablePrefix must be a plain SQL identifier of at most 40 characters");
        }
        retention = positive("retention", retention);
    }

    /** Builds settings from an already resolved durable identity. */
    public DurableJournalSettings(
            DurableJournalIdentity identity,
            boolean enabled,
            Duration flushInterval,
            int maxPendingEntries,
            long maxPendingBytes,
            int hotMaxEntries,
            String tablePrefix,
            Duration retention,
            boolean readerArchiveCrossNamespace) {
        this(
                Objects.requireNonNull(identity, "identity must not be null").namespaceId(),
                identity.applicationId(),
                identity.runId(),
                identity.processId(),
                identity.instanceId(),
                enabled,
                flushInterval,
                maxPendingEntries,
                maxPendingBytes,
                hotMaxEntries,
                tablePrefix,
                retention,
                readerArchiveCrossNamespace);
    }

    /** The run's complete durable storage scope. */
    public DurableJournalScope scope() {
        return new DurableJournalScope(namespaceId, applicationId, runId);
    }

    /** The identity portion of these settings. */
    public DurableJournalIdentity identity() {
        return new DurableJournalIdentity(namespaceId, applicationId, runId, processId, instanceId);
    }

    private static Duration positive(String field, Duration value) {
        Duration duration = Objects.requireNonNull(value, field + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    private static int positive(String field, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static long positive(String field, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
