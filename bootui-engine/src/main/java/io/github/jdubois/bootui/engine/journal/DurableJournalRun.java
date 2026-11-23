package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * Durable metadata and progress for one application run.
 *
 * <p>The metadata is deliberately opaque: the journal store preserves it without introducing a JSON or framework
 * dependency.
 *
 * @param scope the run's full tenant key
 * @param startedAtEpochMillis wall-clock time the run started
 * @param updatedAtEpochMillis wall-clock time this metadata was last updated
 * @param status bounded, caller-defined lifecycle status
 * @param metadata optional opaque metadata
 * @param metadataPayload optional bounded binary metadata
 * @param checkpoint the greatest event sequence durably consumed by the caller
 * @param instanceId application instance that produced the run, or {@code null}
 */
public record DurableJournalRun(
        DurableJournalScope scope,
        long startedAtEpochMillis,
        long updatedAtEpochMillis,
        String status,
        String metadata,
        byte[] metadataPayload,
        long checkpoint,
        String instanceId) {

    public static final int MAX_STATUS_LENGTH = 32;
    public static final int MAX_METADATA_LENGTH = 4_000;
    public static final int MAX_METADATA_PAYLOAD_LENGTH = DurableJournalRunMetadataCodec.MAX_FRAMED_BYTES;
    public static final int MAX_INSTANCE_ID_LENGTH = 128;

    public DurableJournalRun {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        status = DurableJournalScope.bounded("status", status, MAX_STATUS_LENGTH);
        if (metadata != null && metadata.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("metadata exceeds " + MAX_METADATA_LENGTH + " characters");
        }
        if (metadataPayload != null) {
            if (metadataPayload.length > MAX_METADATA_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("metadataPayload exceeds " + MAX_METADATA_PAYLOAD_LENGTH + " bytes");
            }
            metadataPayload = metadataPayload.clone();
        }
        if (checkpoint < 0) {
            throw new IllegalArgumentException("checkpoint must not be negative");
        }
        if (instanceId != null && (instanceId.isBlank() || instanceId.length() > MAX_INSTANCE_ID_LENGTH)) {
            throw new IllegalArgumentException(
                    "instanceId must be blank or at most " + MAX_INSTANCE_ID_LENGTH + " characters");
        }
    }

    public DurableJournalRun(
            DurableJournalScope scope,
            long startedAtEpochMillis,
            long updatedAtEpochMillis,
            String status,
            String metadata,
            long checkpoint) {
        this(scope, startedAtEpochMillis, updatedAtEpochMillis, status, metadata, null, checkpoint, null);
    }

    public DurableJournalRun(
            DurableJournalScope scope,
            long startedAtEpochMillis,
            long updatedAtEpochMillis,
            String status,
            String metadata,
            long checkpoint,
            String instanceId) {
        this(scope, startedAtEpochMillis, updatedAtEpochMillis, status, metadata, null, checkpoint, instanceId);
    }

    /** Convenience constructor for callers holding a decoded metadata snapshot. */
    public DurableJournalRun(
            DurableJournalScope scope,
            long startedAtEpochMillis,
            long updatedAtEpochMillis,
            String status,
            String metadata,
            long checkpoint,
            String instanceId,
            DurableJournalRunMetadata runMetadata) {
        this(
                scope,
                startedAtEpochMillis,
                updatedAtEpochMillis,
                status,
                metadata,
                DurableJournalRunMetadataCodec.encode(
                        Objects.requireNonNull(runMetadata, "runMetadata must not be null")),
                checkpoint,
                instanceId);
    }

    @Override
    public byte[] metadataPayload() {
        return metadataPayload == null ? null : metadataPayload.clone();
    }

    /** This run with its progress advanced to {@code sequence}; progress never moves backwards. */
    public DurableJournalRun withCheckpoint(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("checkpoint must not be negative");
        }
        return new DurableJournalRun(
                scope,
                startedAtEpochMillis,
                updatedAtEpochMillis,
                status,
                metadata,
                metadataPayload,
                Math.max(checkpoint, sequence),
                instanceId);
    }

    /** This run with the supplied status and update time. */
    public DurableJournalRun withStatus(String newStatus, long updatedAt) {
        return new DurableJournalRun(
                scope, startedAtEpochMillis, updatedAt, newStatus, metadata, metadataPayload, checkpoint, instanceId);
    }

    /** This run with a defensive copy of the supplied opaque binary metadata. */
    public DurableJournalRun withMetadataPayload(byte[] newMetadataPayload) {
        return new DurableJournalRun(
                scope,
                startedAtEpochMillis,
                updatedAtEpochMillis,
                status,
                metadata,
                newMetadataPayload,
                checkpoint,
                instanceId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DurableJournalRun run)) {
            return false;
        }
        return startedAtEpochMillis == run.startedAtEpochMillis
                && updatedAtEpochMillis == run.updatedAtEpochMillis
                && checkpoint == run.checkpoint
                && scope.equals(run.scope)
                && status.equals(run.status)
                && Objects.equals(metadata, run.metadata)
                && java.util.Arrays.equals(metadataPayload, run.metadataPayload)
                && Objects.equals(instanceId, run.instanceId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                scope, startedAtEpochMillis, updatedAtEpochMillis, status, metadata, checkpoint, instanceId);
        return 31 * result + java.util.Arrays.hashCode(metadataPayload);
    }
}
