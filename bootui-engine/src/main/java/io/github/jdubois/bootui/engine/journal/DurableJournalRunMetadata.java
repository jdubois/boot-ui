package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.insights.InsightsStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded, framework-neutral snapshot of the metadata needed to interpret one durable journal run.
 *
 * <p>The snapshot is independent of any durable store. Producers can write it at {@link Phase#START},
 * {@link Phase#CHECKPOINT}, and {@link Phase#END}; a checkpoint without an end snapshot remains useful after a crash.
 * All potentially mutable collections are defensively copied. The SQL qualification is represented by counts and
 * fingerprints, never by data source names, and the summary is encoded by {@link RunSummaryCodec} with its existing
 * literal-free and size bounds. Capture loss counters and the last clear fence distinguish intentionally deleted
 * recording generations from durable-reader gaps.</p>
 */
public record DurableJournalRunMetadata(
        Phase phase,
        long capturedAtEpochMillis,
        String instanceId,
        String processId,
        InsightsStack stack,
        String stackName,
        RuntimeJournalSettings journalSettings,
        JournalStatus journalStatus,
        Long lossHorizonMillis,
        Long lostRequestEndMillis,
        List<String> lostRequestTraces,
        List<String> lostRequestThreads,
        Map<JournalSource, Long> lostBySource,
        SqlCaptureQualification sqlCaptureQualification,
        Map<String, Integer> poolSizes,
        DeclaredRoutes declaredRoutes,
        RunSummary runSummary,
        long durableDroppedEvents,
        long durableQuarantinedEvents,
        long durableClearedThrough,
        OffloadTraceLoss offloadTraceLoss) {

    public static DurableJournalRunMetadata snapshot(
            Phase phase,
            RuntimeJournal journal,
            DurableJournalIdentity identity,
            InsightsStack stack,
            Map<String, Integer> poolSizes,
            DeclaredRoutes declaredRoutes,
            RunSummary runSummary) {
        Objects.requireNonNull(journal, "journal must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Map<JournalSource, Long> lostBySource = new java.util.EnumMap<>(JournalSource.class);
        for (JournalSource source : JournalSource.values()) {
            Long lost = journal.lostMillis(source);
            if (lost != null) {
                lostBySource.put(source, lost);
            }
        }
        RuntimeJournal.DurableTraceSnapshot traceSnapshot = journal.durableTraceSnapshot(identity.scope());
        return new DurableJournalRunMetadata(
                phase,
                System.currentTimeMillis(),
                identity.instanceId(),
                identity.processId(),
                stack,
                stack.name(),
                journal.settings(),
                traceSnapshot.status(),
                journal.lossHorizonMillis(),
                journal.lostRequestEndMillis(),
                boundedRecentValid(journal.lostRequestTraces(), MAX_LOST_REQUEST_TRACES),
                boundedRecentValid(journal.lostRequestThreads(), MAX_LOST_REQUEST_THREADS),
                lostBySource,
                SqlCaptureQualification.of(journal.sqlCaptureScope()),
                poolSizes,
                declaredRoutes,
                runSummary,
                0,
                0,
                0,
                traceSnapshot.offloadTraceLoss());
    }

    private static List<String> boundedRecentValid(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> valid = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .filter(value -> value.length() <= MAX_IDENTIFIER_LENGTH)
                .filter(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_LOST_VALUE_BYTES)
                .toList();
        return List.copyOf(valid.subList(Math.max(0, valid.size() - limit), valid.size()));
    }

    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final int MAX_IDENTIFIER_BYTES = 512;
    public static final int MAX_STACK_NAME_BYTES = 128;
    public static final int MAX_LOST_REQUEST_TRACES = 1_024;
    public static final int MAX_LOST_REQUEST_THREADS = 1_024;
    public static final int MAX_LOST_VALUE_BYTES = 512;
    public static final int MAX_POOL_SIZES = 128;
    public static final int MAX_POOL_NAME_BYTES = 512;
    public static final int MAX_ROUTE_REASON_BYTES = 1_024;
    public static final int MAX_DECLARED_ROUTE_COUNT = 10_000;

    public DurableJournalRunMetadata {
        Objects.requireNonNull(phase, "phase must not be null");
        if (capturedAtEpochMillis < 0) {
            throw new IllegalArgumentException("capturedAtEpochMillis must not be negative");
        }
        instanceId = bounded("instanceId", instanceId, MAX_IDENTIFIER_LENGTH, MAX_IDENTIFIER_BYTES);
        processId = bounded("processId", processId, MAX_IDENTIFIER_LENGTH, MAX_IDENTIFIER_BYTES);
        Objects.requireNonNull(stack, "stack must not be null");
        stackName = bounded("stackName", stackName, MAX_IDENTIFIER_LENGTH, MAX_STACK_NAME_BYTES);
        Objects.requireNonNull(journalSettings, "journalSettings must not be null");
        Objects.requireNonNull(journalStatus, "journalStatus must not be null");
        validateStatus(journalStatus);
        if (lossHorizonMillis != null && lossHorizonMillis < 0) {
            throw new IllegalArgumentException("lossHorizonMillis must not be negative");
        }
        if (lostRequestEndMillis != null && lostRequestEndMillis < 0) {
            throw new IllegalArgumentException("lostRequestEndMillis must not be negative");
        }
        lostRequestTraces = immutableBoundedStrings("lostRequestTraces", lostRequestTraces, MAX_LOST_REQUEST_TRACES);
        lostRequestThreads =
                immutableBoundedStrings("lostRequestThreads", lostRequestThreads, MAX_LOST_REQUEST_THREADS);
        lostBySource = immutableLostSources(lostBySource);
        poolSizes = immutablePoolSizes(poolSizes);
        if (durableDroppedEvents < 0 || durableQuarantinedEvents < 0 || durableClearedThrough < 0) {
            throw new IllegalArgumentException("durable capture loss counters must not be negative");
        }
        Objects.requireNonNull(offloadTraceLoss, "offloadTraceLoss must not be null");
    }

    /** A snapshot using the enum name as its durable stack name. */
    public DurableJournalRunMetadata(
            Phase phase,
            long capturedAtEpochMillis,
            String instanceId,
            String processId,
            InsightsStack stack,
            RuntimeJournalSettings journalSettings,
            JournalStatus journalStatus,
            Long lossHorizonMillis,
            Long lostRequestEndMillis,
            List<String> lostRequestTraces,
            List<String> lostRequestThreads,
            Map<JournalSource, Long> lostBySource,
            SqlCaptureQualification sqlCaptureQualification,
            Map<String, Integer> poolSizes,
            DeclaredRoutes declaredRoutes,
            RunSummary runSummary) {
        this(
                phase,
                capturedAtEpochMillis,
                instanceId,
                processId,
                stack,
                Objects.requireNonNull(stack, "stack must not be null").name(),
                journalSettings,
                journalStatus,
                lossHorizonMillis,
                lostRequestEndMillis,
                lostRequestTraces,
                lostRequestThreads,
                lostBySource,
                sqlCaptureQualification,
                poolSizes,
                declaredRoutes,
                runSummary,
                0,
                0,
                0,
                OffloadTraceLoss.NONE);
    }

    /** Compatibility constructor before durable-capture loss counters were persisted. */
    public DurableJournalRunMetadata(
            Phase phase,
            long capturedAtEpochMillis,
            String instanceId,
            String processId,
            InsightsStack stack,
            String stackName,
            RuntimeJournalSettings journalSettings,
            JournalStatus journalStatus,
            Long lossHorizonMillis,
            Long lostRequestEndMillis,
            List<String> lostRequestTraces,
            List<String> lostRequestThreads,
            Map<JournalSource, Long> lostBySource,
            SqlCaptureQualification sqlCaptureQualification,
            Map<String, Integer> poolSizes,
            DeclaredRoutes declaredRoutes,
            RunSummary runSummary) {
        this(
                phase,
                capturedAtEpochMillis,
                instanceId,
                processId,
                stack,
                stackName,
                journalSettings,
                journalStatus,
                lossHorizonMillis,
                lostRequestEndMillis,
                lostRequestTraces,
                lostRequestThreads,
                lostBySource,
                sqlCaptureQualification,
                poolSizes,
                declaredRoutes,
                runSummary,
                0,
                0,
                0,
                OffloadTraceLoss.NONE);
    }

    /** This metadata with the capture queue's acknowledged loss and poison-row counts. */
    public DurableJournalRunMetadata withCaptureLoss(long dropped, long quarantined, long clearedThrough) {
        return new DurableJournalRunMetadata(
                phase,
                capturedAtEpochMillis,
                instanceId,
                processId,
                stack,
                stackName,
                journalSettings,
                journalStatus,
                lossHorizonMillis,
                lostRequestEndMillis,
                lostRequestTraces,
                lostRequestThreads,
                lostBySource,
                sqlCaptureQualification,
                poolSizes,
                declaredRoutes,
                runSummary,
                dropped,
                quarantined,
                clearedThrough,
                offloadTraceLoss);
    }

    /** Run-scoped fingerprints only; overflow vetoes trace-only ownership until an explicit clear. */
    public record OffloadTraceLoss(java.util.Set<String> fingerprints, boolean overflowed) {
        static final int MAX_FINGERPRINTS = 256;
        static final int FINGERPRINT_BYTES = 32;
        static final OffloadTraceLoss NONE = new OffloadTraceLoss(java.util.Set.of(), false);
        static final OffloadTraceLoss OVERFLOW = new OffloadTraceLoss(java.util.Set.of(), true);

        public OffloadTraceLoss {
            fingerprints = java.util.Set.copyOf(Objects.requireNonNull(fingerprints));
            if (fingerprints.size() > MAX_FINGERPRINTS
                    || overflowed && !fingerprints.isEmpty()
                    || fingerprints.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid offload trace loss fingerprints");
            }
        }

        boolean matches(DurableJournalScope scope, String traceId) {
            return traceId != null
                    && (overflowed
                            || !fingerprints.isEmpty()
                                    && fingerprints.contains(
                                            DurableJournalIds.offloadTraceFingerprint(scope, traceId)));
        }

        boolean isEmpty() {
            return !overflowed && fingerprints.isEmpty();
        }
    }

    /** When this metadata was written in the life of a run. A crash leaves the latest non-end snapshot readable. */
    public enum Phase {
        START,
        CHECKPOINT,
        END
    }

    /**
     * A qualification signature for SQL feeders. It deliberately omits their data source names: counts and SHA-256
     * fingerprints retain comparability without persisting raw identifiers.
     */
    public record SqlCaptureQualification(
            int executionCount,
            int preparationCount,
            boolean incomplete,
            String executionFingerprint,
            String preparationFingerprint) {

        public SqlCaptureQualification {
            if (executionCount < 0
                    || executionCount > SqlCaptureScopes.MAX_SOURCES
                    || preparationCount < 0
                    || preparationCount > SqlCaptureScopes.MAX_SOURCES) {
                throw new IllegalArgumentException("SQL capture source counts exceed the public bounded scope");
            }
            executionFingerprint = fingerprint("executionFingerprint", executionFingerprint);
            preparationFingerprint = fingerprint("preparationFingerprint", preparationFingerprint);
            if (executionCount == 0 && executionFingerprint != null
                    || executionCount > 0 && executionFingerprint == null
                    || preparationCount == 0 && preparationFingerprint != null
                    || preparationCount > 0 && preparationFingerprint == null) {
                throw new IllegalArgumentException("SQL capture fingerprints must match their source counts");
            }
        }

        /** A safe durable view of the runtime journal's public SQL qualification snapshot. */
        public static SqlCaptureQualification of(SqlCaptureScopes.Snapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            return new SqlCaptureQualification(
                    snapshot.executions().size(),
                    snapshot.preparations().size(),
                    snapshot.incomplete(),
                    snapshot.fingerprint(true),
                    snapshot.fingerprint(false));
        }

        private static String fingerprint(String field, String value) {
            if (value == null) {
                return null;
            }
            if (value.length() != 64
                    || !value.chars()
                            .allMatch(character ->
                                    character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256 fingerprint");
            }
            return value;
        }
    }

    /** Safe aggregate metadata for the application's declared HTTP routes. */
    public record DeclaredRoutes(int count, boolean overflowed, String incompleteReason) {

        public DeclaredRoutes {
            if (count < 0 || count > MAX_DECLARED_ROUTE_COUNT) {
                throw new IllegalArgumentException("Declared route count is out of bounds");
            }
            incompleteReason = nullableBounded("incompleteReason", incompleteReason, MAX_ROUTE_REASON_BYTES);
        }
    }

    private static List<String> immutableBoundedStrings(String field, List<String> values, int maxItems) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxItems) {
            throw new IllegalArgumentException(field + " exceeds " + maxItems + " items");
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(bounded(field, value, MAX_IDENTIFIER_LENGTH, MAX_LOST_VALUE_BYTES));
        }
        return List.copyOf(copy);
    }

    private static Map<JournalSource, Long> immutableLostSources(Map<JournalSource, Long> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > JournalSource.values().length) {
            throw new IllegalArgumentException("lostBySource has too many entries");
        }
        Map<JournalSource, Long> copy = new java.util.EnumMap<>(JournalSource.class);
        values.forEach((source, epochMillis) -> {
            Objects.requireNonNull(source, "lostBySource key must not be null");
            if (epochMillis == null || epochMillis < 0) {
                throw new IllegalArgumentException("lostBySource values must not be null or negative");
            }
            copy.put(source, epochMillis);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Integer> immutablePoolSizes(Map<String, Integer> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > MAX_POOL_SIZES) {
            throw new IllegalArgumentException("poolSizes exceeds " + MAX_POOL_SIZES + " entries");
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        values.forEach((name, size) -> {
            String key = bounded("poolSizes key", name, MAX_IDENTIFIER_LENGTH, MAX_POOL_NAME_BYTES);
            if (size == null || size < -1) {
                throw new IllegalArgumentException("poolSizes values must be -1 or non-negative");
            }
            copy.put(key, size);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void validateStatus(JournalStatus status) {
        bounded("journalStatus.instanceId", status.instanceId(), MAX_IDENTIFIER_LENGTH, MAX_IDENTIFIER_BYTES);
        bounded("journalStatus.runId", status.runId(), MAX_IDENTIFIER_LENGTH, MAX_IDENTIFIER_BYTES);
        if (status.bindingBound() != null
                && !status.bindingBound().equals("COUNT")
                && !status.bindingBound().equals("BYTES")) {
            throw new IllegalArgumentException("journalStatus.bindingBound must be null, COUNT, or BYTES");
        }
        if (status.lastSequence() < 0
                || status.retainedEvents() < 0
                || status.retainedEvents() > status.maxEvents()
                || status.retainedBytes() < 0
                || status.dictionaryEntries() < 0
                || status.dictionaryBytes() < 0
                || status.maxEvents() < 1
                || status.maxBytes() < 1
                || status.reserved() < 0
                || status.reservedCapacity() < 0
                || status.reserved() > status.reservedCapacity()
                || status.reserved() > status.retainedEvents()
                || status.evictedByCount() < 0
                || status.evictedByBytes() < 0
                || status.oldestRetainedEpochMillis() != null && status.oldestRetainedEpochMillis() < 0
                || status.queueDepth() < 0
                || status.queueCapacity() < 0
                || status.queueDepth() > status.queueCapacity()
                || status.accepted().values().stream().anyMatch(value -> value == null || value < 0)
                || status.dropped().values().stream().anyMatch(value -> value == null || value < 0)
                || status.listenerFailures() < 0
                || status.clears() < 0
                || status.offloadedEvents() < 0
                || status.durableOffloadedThrough() < 0
                || status.durableOffloadedThrough() > status.lastSequence()) {
            throw new IllegalArgumentException("journalStatus has invalid counters or bounds");
        }
    }

    static String bounded(String field, String value, int maxCharacters, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        if (value.length() > maxCharacters || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return value;
    }

    static String nullableBounded(String field, String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return value;
    }
}
