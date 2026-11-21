package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.insights.InsightsStack;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict, versioned binary representation of {@link DurableJournalRunMetadata}.
 *
 * <p>The format uses JDK primitives only and never Java serialization. It is capped so untrusted persisted bytes
 * cannot cause unbounded collection or string allocation. A nested {@link RunSummary} is re-encoded by
 * {@link RunSummaryCodec} into its established bounded, literal-free format.</p>
 */
public final class DurableJournalRunMetadataCodec {

    public static final int VERSION = 3;
    private static final int MAX_BASE_BYTES = 320 * 1024;
    public static final int MAX_ENCODED_BYTES = MAX_BASE_BYTES
            + Integer.BYTES
            + DurableJournalRunMetadata.OffloadTraceLoss.MAX_FINGERPRINTS
                    * DurableJournalRunMetadata.OffloadTraceLoss.FINGERPRINT_BYTES;
    public static final int MAX_SUMMARY_BYTES = RunHistory.MAX_SUMMARY_BYTES;

    private static final int MAGIC = 0x42554a4d;

    private DurableJournalRunMetadataCodec() {}

    /** Encodes one current metadata snapshot, including its optional bounded summary. */
    public static byte[] encode(DurableJournalRunMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        byte[] summary =
                metadata.runSummary() == null ? null : RunSummaryCodec.encode(metadata.runSummary(), MAX_SUMMARY_BYTES);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            var traceLoss = metadata.offloadTraceLoss();
            out.writeInt(traceLoss.isEmpty() ? 2 : VERSION);
            out.writeByte(metadata.phase().ordinal());
            out.writeLong(metadata.capturedAtEpochMillis());
            writeString(out, metadata.instanceId());
            writeString(out, metadata.processId());
            out.writeByte(metadata.stack().ordinal());
            writeString(out, metadata.stackName());
            writeSettings(out, metadata.journalSettings());
            writeStatus(out, metadata.journalStatus());
            out.writeBoolean(metadata.lossHorizonMillis() != null);
            if (metadata.lossHorizonMillis() != null) {
                out.writeLong(metadata.lossHorizonMillis());
            }
            out.writeBoolean(metadata.lostRequestEndMillis() != null);
            if (metadata.lostRequestEndMillis() != null) {
                out.writeLong(metadata.lostRequestEndMillis());
            }
            writeStrings(out, metadata.lostRequestTraces());
            writeStrings(out, metadata.lostRequestThreads());
            writeLostSources(out, metadata.lostBySource());
            out.writeBoolean(metadata.sqlCaptureQualification() != null);
            if (metadata.sqlCaptureQualification() != null) {
                writeSqlQualification(out, metadata.sqlCaptureQualification());
            }
            out.writeInt(metadata.poolSizes().size());
            for (Map.Entry<String, Integer> entry : metadata.poolSizes().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                writeString(out, entry.getKey());
                out.writeInt(entry.getValue());
            }
            out.writeBoolean(metadata.declaredRoutes() != null);
            if (metadata.declaredRoutes() != null) {
                out.writeInt(metadata.declaredRoutes().count());
                out.writeBoolean(metadata.declaredRoutes().overflowed());
                writeNullableString(out, metadata.declaredRoutes().incompleteReason());
            }
            out.writeBoolean(summary != null);
            if (summary != null) {
                writeBytes(out, summary);
            }
            out.writeLong(metadata.durableDroppedEvents());
            out.writeLong(metadata.durableQuarantinedEvents());
            out.writeLong(metadata.durableClearedThrough());
            if (bytes.size() > MAX_BASE_BYTES) {
                throw new IllegalArgumentException(
                        "Durable journal run metadata exceeds " + MAX_BASE_BYTES + " base bytes");
            }
            if (!traceLoss.isEmpty()) {
                int count = traceLoss.fingerprints().size();
                out.writeInt(traceLoss.overflowed() ? -1 : traceLoss.onlyUntraced() ? -2 : count);
                for (String fingerprint :
                        traceLoss.fingerprints().stream().sorted().toList()) {
                    out.write(java.util.HexFormat.of().parseHex(fingerprint));
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot encode durable journal run metadata", ex);
        }
    }

    /** Strictly decodes one current metadata snapshot. */
    public static DurableJournalRunMetadata decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Durable journal run metadata bytes must not be null");
        }
        if (bytes.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Durable journal run metadata exceeds " + MAX_ENCODED_BYTES + " bytes");
        }
        try {
            In in = new In(bytes);
            if (in.data.readInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid durable journal run metadata magic");
            }
            int version = in.data.readInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException("Unsupported durable journal run metadata version " + version);
            }
            DurableJournalRunMetadata.Phase phase = in.enumeration(DurableJournalRunMetadata.Phase.class);
            long capturedAtEpochMillis = in.data.readLong();
            String instanceId = in.string(DurableJournalRunMetadata.MAX_IDENTIFIER_BYTES);
            String processId = in.string(DurableJournalRunMetadata.MAX_IDENTIFIER_BYTES);
            InsightsStack stack = in.enumeration(InsightsStack.class);
            String stackName = in.string(DurableJournalRunMetadata.MAX_STACK_NAME_BYTES);
            RuntimeJournalSettings settings = in.settings();
            JournalStatus status = in.status(version >= 2);
            Long lossHorizonMillis = in.data.readBoolean() ? in.data.readLong() : null;
            Long lostRequestEndMillis = in.data.readBoolean() ? in.data.readLong() : null;
            List<String> lostRequestTraces = in.strings(
                    DurableJournalRunMetadata.MAX_LOST_REQUEST_TRACES, DurableJournalRunMetadata.MAX_LOST_VALUE_BYTES);
            List<String> lostRequestThreads = in.strings(
                    DurableJournalRunMetadata.MAX_LOST_REQUEST_THREADS, DurableJournalRunMetadata.MAX_LOST_VALUE_BYTES);
            Map<JournalSource, Long> lostBySource = in.lostSources();
            DurableJournalRunMetadata.SqlCaptureQualification sqlQualification =
                    in.data.readBoolean() ? in.sqlQualification() : null;
            Map<String, Integer> poolSizes = in.poolSizes();
            DurableJournalRunMetadata.DeclaredRoutes routes = in.data.readBoolean()
                    ? new DurableJournalRunMetadata.DeclaredRoutes(
                            in.data.readInt(),
                            in.data.readBoolean(),
                            in.nullableString(DurableJournalRunMetadata.MAX_ROUTE_REASON_BYTES))
                    : null;
            RunSummary summary = null;
            if (in.data.readBoolean()) {
                byte[] encodedSummary = in.bytes(MAX_SUMMARY_BYTES);
                summary = RunSummaryCodec.decodeStrict(encodedSummary);
            }
            long durableDroppedEvents = in.data.readLong();
            long durableQuarantinedEvents = in.data.readLong();
            long durableClearedThrough = in.data.readLong();
            if (bytes.length - in.data.available() > MAX_BASE_BYTES) {
                throw new IllegalArgumentException(
                        "Durable journal run metadata exceeds " + MAX_BASE_BYTES + " base bytes");
            }
            var traceLoss = version >= 3 ? in.offloadTraceLoss() : DurableJournalRunMetadata.OffloadTraceLoss.NONE;
            in.finish();
            return new DurableJournalRunMetadata(
                    phase,
                    capturedAtEpochMillis,
                    instanceId,
                    processId,
                    stack,
                    stackName,
                    settings,
                    status,
                    lossHorizonMillis,
                    lostRequestEndMillis,
                    lostRequestTraces,
                    lostRequestThreads,
                    lostBySource,
                    sqlQualification,
                    poolSizes,
                    routes,
                    summary,
                    durableDroppedEvents,
                    durableQuarantinedEvents,
                    durableClearedThrough,
                    traceLoss);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Malformed durable journal run metadata", ex);
        }
    }

    private static void writeSettings(DataOutputStream out, RuntimeJournalSettings settings) throws IOException {
        out.writeBoolean(settings.enabled());
        out.writeInt(settings.maxEvents());
        out.writeLong(settings.maxBytes());
        out.writeInt(settings.queueCapacity());
        out.writeInt(settings.reservedSharePercent());
        out.writeInt(settings.reservedQueueSharePercent());
        writeSources(out, settings.sources());
    }

    private static void writeStatus(DataOutputStream out, JournalStatus status) throws IOException {
        out.writeBoolean(status.enabled());
        writeString(out, status.instanceId());
        writeString(out, status.runId());
        out.writeLong(status.lastSequence());
        out.writeInt(status.retainedEvents());
        out.writeLong(status.retainedBytes());
        out.writeInt(status.dictionaryEntries());
        out.writeLong(status.dictionaryBytes());
        out.writeInt(status.maxEvents());
        out.writeLong(status.maxBytes());
        out.writeInt(status.reserved());
        out.writeInt(status.reservedCapacity());
        out.writeLong(status.evictedByCount());
        out.writeLong(status.evictedByBytes());
        writeNullableString(out, status.bindingBound());
        writeNullableLong(out, status.oldestRetainedEpochMillis());
        out.writeInt(status.queueDepth());
        out.writeInt(status.queueCapacity());
        writeSourceLongMap(out, status.accepted());
        writeSourceLongMap(out, status.dropped());
        out.writeLong(status.listenerFailures());
        out.writeLong(status.clears());
        out.writeLong(status.offloadedEvents());
        out.writeLong(status.durableOffloadedThrough());
    }

    private static void writeSources(DataOutputStream out, Iterable<JournalSource> sources) throws IOException {
        long bits = 0;
        for (JournalSource source : sources) {
            bits |= 1L << source.ordinal();
        }
        out.writeLong(bits);
    }

    private static void writeLostSources(DataOutputStream out, Map<JournalSource, Long> lost) throws IOException {
        out.writeInt(lost.size());
        for (Map.Entry<JournalSource, Long> entry :
                lost.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            out.writeByte(entry.getKey().ordinal());
            out.writeLong(entry.getValue());
        }
    }

    private static void writeSourceLongMap(DataOutputStream out, Map<JournalSource, Long> values) throws IOException {
        if (values == null) {
            values = Map.of();
        }
        out.writeInt(values.size());
        for (Map.Entry<JournalSource, Long> entry :
                values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            out.writeByte(entry.getKey().ordinal());
            out.writeLong(entry.getValue());
        }
    }

    private static void writeSqlQualification(
            DataOutputStream out, DurableJournalRunMetadata.SqlCaptureQualification qualification) throws IOException {
        out.writeInt(qualification.executionCount());
        out.writeInt(qualification.preparationCount());
        out.writeBoolean(qualification.incomplete());
        writeNullableString(out, qualification.executionFingerprint());
        writeNullableString(out, qualification.preparationFingerprint());
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "string must not be null").getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class In {

        private final DataInputStream data;

        In(byte[] bytes) {
            data = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        }

        DurableJournalRunMetadata.OffloadTraceLoss offloadTraceLoss() throws IOException {
            int count = data.readInt();
            if (count == -1) {
                return DurableJournalRunMetadata.OffloadTraceLoss.OVERFLOW;
            }
            if (count == -2) {
                return DurableJournalRunMetadata.OffloadTraceLoss.UNTRACED;
            }
            if (count < 1 || count > DurableJournalRunMetadata.OffloadTraceLoss.MAX_FINGERPRINTS) {
                throw new IllegalArgumentException("Invalid offload trace fingerprint count");
            }
            byte[] digest = new byte[DurableJournalRunMetadata.OffloadTraceLoss.FINGERPRINT_BYTES];
            var fingerprints = new java.util.HashSet<String>();
            for (int i = 0; i < count; i++) {
                data.readFully(digest);
                if (!fingerprints.add(java.util.HexFormat.of().formatHex(digest))) {
                    throw new IllegalArgumentException("Duplicate offload trace fingerprint");
                }
            }
            return new DurableJournalRunMetadata.OffloadTraceLoss(fingerprints, false);
        }

        RuntimeJournalSettings settings() throws IOException {
            boolean enabled = data.readBoolean();
            int maxEvents = data.readInt();
            long maxBytes = data.readLong();
            int queueCapacity = data.readInt();
            int reservedShare = data.readInt();
            int reservedQueueShare = data.readInt();
            return new RuntimeJournalSettings(
                    enabled, maxEvents, maxBytes, queueCapacity, reservedShare, reservedQueueShare, sourceSet());
        }

        JournalStatus status(boolean includesOffload) throws IOException, CharacterCodingException {
            boolean enabled = data.readBoolean();
            String instanceId = string(DurableJournalRunMetadata.MAX_IDENTIFIER_BYTES);
            String runId = string(DurableJournalRunMetadata.MAX_IDENTIFIER_BYTES);
            long lastSequence = data.readLong();
            int retainedEvents = data.readInt();
            long retainedBytes = data.readLong();
            int dictionaryEntries = data.readInt();
            long dictionaryBytes = data.readLong();
            int maxEvents = data.readInt();
            long maxBytes = data.readLong();
            int reserved = data.readInt();
            int reservedCapacity = data.readInt();
            long evictedByCount = data.readLong();
            long evictedByBytes = data.readLong();
            String bindingBound = nullableString(32);
            Long oldest = data.readBoolean() ? data.readLong() : null;
            int queueDepth = data.readInt();
            int queueCapacity = data.readInt();
            Map<JournalSource, Long> accepted = sourceLongMap();
            Map<JournalSource, Long> dropped = sourceLongMap();
            long listenerFailures = data.readLong();
            long clears = data.readLong();
            long offloadedEvents = includesOffload ? data.readLong() : 0;
            long durableOffloadedThrough = includesOffload ? data.readLong() : 0;
            validateStatus(
                    lastSequence,
                    retainedEvents,
                    retainedBytes,
                    dictionaryEntries,
                    dictionaryBytes,
                    maxEvents,
                    maxBytes,
                    reserved,
                    reservedCapacity,
                    evictedByCount,
                    evictedByBytes,
                    queueDepth,
                    queueCapacity,
                    accepted,
                    dropped,
                    listenerFailures,
                    clears,
                    offloadedEvents,
                    durableOffloadedThrough);
            return new JournalStatus(
                    enabled,
                    instanceId,
                    runId,
                    lastSequence,
                    retainedEvents,
                    retainedBytes,
                    dictionaryEntries,
                    dictionaryBytes,
                    maxEvents,
                    maxBytes,
                    reserved,
                    reservedCapacity,
                    evictedByCount,
                    evictedByBytes,
                    bindingBound,
                    oldest,
                    queueDepth,
                    queueCapacity,
                    accepted,
                    dropped,
                    listenerFailures,
                    clears,
                    offloadedEvents,
                    durableOffloadedThrough);
        }

        DurableJournalRunMetadata.SqlCaptureQualification sqlQualification()
                throws IOException, CharacterCodingException {
            return new DurableJournalRunMetadata.SqlCaptureQualification(
                    data.readInt(), data.readInt(), data.readBoolean(), nullableString(64), nullableString(64));
        }

        Map<String, Integer> poolSizes() throws IOException, CharacterCodingException {
            int count = count(DurableJournalRunMetadata.MAX_POOL_SIZES, 5);
            Map<String, Integer> values = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = string(DurableJournalRunMetadata.MAX_POOL_NAME_BYTES);
                int size = data.readInt();
                if (values.put(name, size) != null) {
                    throw new IllegalArgumentException("Duplicate durable journal pool name");
                }
            }
            return values;
        }

        Map<JournalSource, Long> lostSources() throws IOException {
            return sourceLongMap();
        }

        private Map<JournalSource, Long> sourceLongMap() throws IOException {
            int count = count(JournalSource.values().length, 9);
            Map<JournalSource, Long> values = new EnumMap<>(JournalSource.class);
            for (int i = 0; i < count; i++) {
                JournalSource source = enumerationByte(JournalSource.class);
                long value = data.readLong();
                if (values.put(source, value) != null) {
                    throw new IllegalArgumentException("Duplicate durable journal source");
                }
            }
            return values;
        }

        List<String> strings(int maxItems, int maxBytes) throws IOException, CharacterCodingException {
            int count = count(maxItems, 4);
            List<String> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(string(maxBytes));
            }
            return values;
        }

        java.util.Set<JournalSource> sourceSet() throws IOException {
            long bits = data.readLong();
            long known = 0;
            for (JournalSource source : JournalSource.values()) {
                known |= 1L << source.ordinal();
            }
            if ((bits & ~known) != 0) {
                throw new IllegalArgumentException("Durable journal source set has unknown bits");
            }
            java.util.EnumSet<JournalSource> sources = java.util.EnumSet.noneOf(JournalSource.class);
            for (JournalSource source : JournalSource.values()) {
                if ((bits & (1L << source.ordinal())) != 0) {
                    sources.add(source);
                }
            }
            return java.util.Collections.unmodifiableSet(sources);
        }

        <E extends Enum<E>> E enumeration(Class<E> type) throws IOException {
            return enumerationByte(type);
        }

        private <E extends Enum<E>> E enumerationByte(Class<E> type) throws IOException {
            int ordinal = Byte.toUnsignedInt(data.readByte());
            E[] constants = type.getEnumConstants();
            if (ordinal >= constants.length) {
                throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " ordinal " + ordinal);
            }
            return constants[ordinal];
        }

        String string(int maxBytes) throws IOException, CharacterCodingException {
            byte[] encoded = bytes(maxBytes);
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
            if (value.isBlank()) {
                throw new IllegalArgumentException("A durable journal string must not be blank");
            }
            return value;
        }

        String nullableString(int maxBytes) throws IOException, CharacterCodingException {
            return data.readBoolean() ? string(maxBytes) : null;
        }

        byte[] bytes(int maxBytes) throws IOException {
            int length = data.readInt();
            if (length < 0 || length > maxBytes || length > data.available()) {
                throw new IllegalArgumentException("Invalid durable journal run metadata byte length " + length);
            }
            byte[] value = new byte[length];
            data.readFully(value);
            return value;
        }

        int count(int maximum, int minimumBytes) throws IOException {
            int count = data.readInt();
            if (count < 0 || count > maximum || (long) count * minimumBytes > data.available()) {
                throw new IllegalArgumentException("Invalid durable journal run metadata item count " + count);
            }
            return count;
        }

        void finish() throws IOException {
            if (data.available() != 0) {
                throw new IllegalArgumentException("Trailing durable journal run metadata data");
            }
        }

        private static void validateStatus(
                long lastSequence,
                int retainedEvents,
                long retainedBytes,
                int dictionaryEntries,
                long dictionaryBytes,
                int maxEvents,
                long maxBytes,
                int reserved,
                int reservedCapacity,
                long evictedByCount,
                long evictedByBytes,
                int queueDepth,
                int queueCapacity,
                Map<JournalSource, Long> accepted,
                Map<JournalSource, Long> dropped,
                long listenerFailures,
                long clears,
                long offloadedEvents,
                long durableOffloadedThrough) {
            if (lastSequence < 0
                    || retainedEvents < 0
                    || retainedBytes < 0
                    || dictionaryEntries < 0
                    || dictionaryBytes < 0
                    || maxEvents < 1
                    || maxBytes < 1
                    || reserved < 0
                    || reservedCapacity < 0
                    || evictedByCount < 0
                    || evictedByBytes < 0
                    || queueDepth < 0
                    || queueCapacity < 0
                    || listenerFailures < 0
                    || clears < 0
                    || offloadedEvents < 0
                    || durableOffloadedThrough < 0
                    || retainedEvents > maxEvents
                    || reserved > retainedEvents
                    || reserved > reservedCapacity
                    || queueDepth > queueCapacity
                    || accepted.values().stream().anyMatch(value -> value < 0)
                    || dropped.values().stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("Invalid durable journal status counters");
            }
        }
    }
}
