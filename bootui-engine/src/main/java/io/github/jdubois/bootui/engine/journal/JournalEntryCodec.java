package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal, lossless binary format for one durable journal entry, except for {@link ThrowableMarks}, which must never
 * be persisted. No masking, truncation, or SQL normalization takes place here; the caller owns write-time exposure
 * policy.
 *
 * <p>Version 1 uses big-endian fixed-width numbers, strict 0/1 booleans, explicitly assigned payload ids (0 means no
 * payload), and enum names rather than ordinals. Nullable strings use a signed character count (-1 means null), then
 * UTF-16 code units, preserving even unpaired surrogates without replacement. Other nullable values use a presence
 * boolean. Lists and maps start with a nonnegative element count. Payload and nested fields follow the explicit order
 * below; changing that order, adding fields, or changing their meaning requires a new version. Payload ids must never
 * be reused. Unknown versions, classes, ids, enum names, malformed lengths, truncation, and trailing data are rejected.
 */
public final class JournalEntryCodec {

    private static final int MAGIC = 0x42554a45; // BUJE
    private static final int VERSION = 1;

    /**
     * ApplicationFrames is not a record. Its public factory recomputes retention accounting, losing the unshared-byte
     * count of an interned instance. Only this known constructor is accessible, never a class named by persisted data.
     */
    private static final Constructor<ApplicationFrames> FRAMES_CONSTRUCTOR = framesConstructor();

    private JournalEntryCodec() {}

    public static byte[] encode(JournalEntry entry) {
        if (entry == null || entry.event() == null) {
            throw new IllegalArgumentException("A journal entry and its event must not be null");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Out out = new Out(new DataOutputStream(bytes));
            out.data.writeInt(MAGIC);
            out.data.writeInt(VERSION);
            out.data.writeLong(entry.sequence());
            out.data.writeInt(entry.estimatedBytes());
            RuntimeEvent event = entry.event();
            out.enumeration(event.source());
            out.data.writeLong(event.epochMillis());
            out.data.writeLong(event.durationNanos());
            out.string(event.requestId());
            out.string(event.executionId());
            out.string(event.traceId());
            out.string(event.thread());
            out.enumeration(event.threadKind());
            out.data.writeBoolean(event.failedOrSlow());
            out.payload(event.payload());
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot encode journal entry", ex);
        }
    }

    public static JournalEntry decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Journal entry bytes must not be null");
        }
        try {
            In in = new In(new DataInputStream(new ByteArrayInputStream(bytes)));
            if (in.data.readInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid journal entry magic");
            }
            int version = in.data.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported journal entry codec version: " + version);
            }
            long sequence = in.data.readLong();
            int estimatedBytes = in.data.readInt();
            RuntimeEvent event = new RuntimeEvent(
                    in.enumeration(JournalSource.class),
                    in.data.readLong(),
                    in.data.readLong(),
                    in.string(),
                    in.string(),
                    in.string(),
                    in.string(),
                    in.enumeration(ThreadKind.class),
                    in.bool(),
                    in.payload());
            if (in.data.available() != 0) {
                throw new IllegalArgumentException("Trailing data after journal entry");
            }
            return new JournalEntry(sequence, event, estimatedBytes);
        } catch (IOException | NullPointerException ex) {
            throw new IllegalArgumentException("Malformed or truncated journal entry", ex);
        }
    }

    private enum PayloadType {
        HTTP(1, HttpPayload.class),
        SQL(2, SqlPayload.class),
        REST_CLIENT(3, RestClientPayload.class),
        EXCEPTION(4, ExceptionPayload.class),
        SECURITY(5, SecurityPayload.class),
        CACHE(6, CachePayload.class),
        SCHEDULED(7, ScheduledPayload.class),
        MESSAGING(8, MessagingPayload.class),
        TRANSACTION(9, TransactionPayload.class),
        CONNECTION(10, ConnectionPayload.class),
        MAIL(11, MailPayload.class),
        FAULT_TOLERANCE(12, FaultTolerancePayload.class),
        AI(13, AiPayload.class),
        AUTHORIZATION(14, AuthorizationPayload.class),
        ORM(15, OrmPayload.class),
        GC(16, GcPayload.class),
        LOG(17, LogPayload.class),
        APP_EVENT(18, AppEventPayload.class),
        LIFECYCLE(19, LifecyclePayload.class),
        WEBSOCKET(20, WebSocketPayload.class),
        ASYNC_HANDOFF(21, AsyncHandoffPayload.class),
        CAUGHT_EXCEPTION(22, CaughtExceptionPayload.class);

        private final int id;
        private final Class<? extends RuntimeEventPayload> type;

        PayloadType(int id, Class<? extends RuntimeEventPayload> type) {
            this.id = id;
            this.type = type;
        }
    }

    private static final Map<Class<?>, PayloadType> PAYLOAD_CLASSES;
    private static final Map<Integer, PayloadType> PAYLOAD_IDS;

    static {
        Map<Class<?>, PayloadType> classes = new LinkedHashMap<>();
        Map<Integer, PayloadType> ids = new LinkedHashMap<>();
        for (PayloadType type : PayloadType.values()) {
            classes.put(type.type, type);
            ids.put(type.id, type);
        }
        PAYLOAD_CLASSES = Map.copyOf(classes);
        PAYLOAD_IDS = Map.copyOf(ids);
    }

    private static final class Out {

        private final DataOutputStream data;

        private Out(DataOutputStream data) {
            this.data = data;
        }

        private void string(String value) throws IOException {
            data.writeInt(value == null ? -1 : value.length());
            if (value != null) {
                for (int i = 0; i < value.length(); i++) {
                    data.writeChar(value.charAt(i));
                }
            }
        }

        private void enumeration(Enum<?> value) throws IOException {
            string(value == null ? null : value.name());
        }

        private void optionalLong(Long value) throws IOException {
            data.writeBoolean(value != null);
            if (value != null) {
                data.writeLong(value);
            }
        }

        private void optionalInt(Integer value) throws IOException {
            data.writeBoolean(value != null);
            if (value != null) {
                data.writeInt(value);
            }
        }

        private void optionalBoolean(Boolean value) throws IOException {
            data.writeBoolean(value != null);
            if (value != null) {
                data.writeBoolean(value);
            }
        }

        private void strings(List<String> values) throws IOException {
            data.writeInt(values.size());
            for (String value : values) {
                string(value);
            }
        }

        private void frames(ApplicationFrames frames) throws IOException {
            data.writeBoolean(frames != null);
            if (frames != null) {
                strings(frames.frames());
                data.writeInt(frames.unsharedBytes());
            }
        }

        private void resources(ResourceUsage resources) throws IOException {
            data.writeBoolean(resources != null);
            if (resources == null) {
                return;
            }
            data.writeLong(resources.cpuNanos());
            data.writeLong(resources.allocatedBytes());
            data.writeInt(resources.segments());
            data.writeInt(resources.unmeasuredSegments());
            enumeration(resources.unmeasuredReason());
            data.writeLong(resources.gcPauses());
            data.writeInt(resources.gcPauseRanges().size());
            for (GcPauseRange range : resources.gcPauseRanges()) {
                string(range.collector());
                data.writeLong(range.afterId());
                data.writeLong(range.lastId());
            }
            data.writeBoolean(resources.gcPauseRangesTruncated());
        }

        private void timing(RequestTiming timing) throws IOException {
            data.writeBoolean(timing != null);
            if (timing != null) {
                data.writeLong(timing.startNanos());
                data.writeLong(timing.authenticationNanos());
                data.writeLong(timing.handlerOffsetNanos());
                data.writeLong(timing.responseOffsetNanos());
            }
        }

        private void runStart(RunStart start) throws IOException {
            data.writeBoolean(start != null);
            if (start == null) {
                return;
            }
            optionalLong(start.readyNanos());
            data.writeInt(start.slowestSteps().size());
            for (StartupStepTiming step : start.slowestSteps()) {
                string(step.name());
                string(step.bean());
                data.writeLong(step.durationNanos());
            }
            ComparabilityFacts facts = start.facts();
            strings(facts.activeProfiles());
            data.writeInt(facts.dataSources().size());
            for (Map.Entry<String, String> source : facts.dataSources().entrySet()) {
                string(source.getKey());
                string(source.getValue());
            }
            string(facts.cacheType());
            data.writeBoolean(facts.tracing());
            strings(facts.journalSources());
        }

        private void payload(RuntimeEventPayload payload) throws IOException {
            if (payload == null) {
                data.writeInt(0);
                return;
            }
            PayloadType type = PAYLOAD_CLASSES.get(payload.getClass());
            if (type == null) {
                throw new IllegalArgumentException("Unsupported journal payload class: "
                        + payload.getClass().getName());
            }
            data.writeInt(type.id);
            switch (type) {
                case HTTP -> {
                    HttpPayload p = (HttpPayload) payload;
                    string(p.method());
                    string(p.path());
                    string(p.routeTemplate());
                    string(p.operation());
                    data.writeInt(p.status());
                    resources(p.resources());
                    timing(p.timing());
                    data.writeBoolean(p.asyncStarted());
                }
                case SQL -> {
                    SqlPayload p = (SqlPayload) payload;
                    string(p.sql());
                    string(p.callSite());
                    string(p.dataSource());
                    data.writeBoolean(p.failed());
                    frames(p.frames());
                    enumeration(p.phase());
                    data.writeLong(p.completedNanos());
                    data.writeLong(p.codePathStamp());
                    enumeration(p.provenance());
                    data.writeInt(p.batchSize());
                    optionalLong(p.rowCount());
                }
                case REST_CLIENT -> {
                    RestClientPayload p = (RestClientPayload) payload;
                    string(p.method());
                    string(p.authority());
                    string(p.path());
                    optionalInt(p.status());
                    string(p.clientType());
                    data.writeBoolean(p.failed());
                    frames(p.frames());
                    data.writeLong(p.completedNanos());
                    data.writeLong(p.codePathStamp());
                }
                case EXCEPTION -> {
                    ExceptionPayload p = (ExceptionPayload) payload;
                    string(p.groupId());
                    string(p.exceptionClass());
                    string(p.signature());
                    strings(p.types());
                    // ThrowableMarks are deliberately absent from the durable format.
                    data.writeBoolean(p.logged());
                }
                case SECURITY -> string(((SecurityPayload) payload).type());
                case CACHE -> {
                    CachePayload p = (CachePayload) payload;
                    string(p.cacheName());
                    string(p.operation());
                    frames(p.frames());
                    data.writeLong(p.codePathStamp());
                }
                case SCHEDULED -> {
                    ScheduledPayload p = (ScheduledPayload) payload;
                    string(p.task());
                    string(p.exceptionClass());
                }
                case MESSAGING -> {
                    MessagingPayload p = (MessagingPayload) payload;
                    string(p.broker());
                    data.writeBoolean(p.sent());
                    string(p.destination());
                    data.writeBoolean(p.failed());
                    string(p.linkedTraceId());
                }
                case TRANSACTION -> {
                    TransactionPayload p = (TransactionPayload) payload;
                    string(p.method());
                    data.writeBoolean(p.rolledBack());
                    data.writeBoolean(p.nested());
                    data.writeBoolean(p.savepoint());
                    data.writeLong(p.startNanos());
                    data.writeBoolean(p.readOnly());
                    string(p.isolation());
                    data.writeBoolean(p.rollbackOnly());
                    string(p.failureClass());
                    data.writeLong(p.ormStatements());
                    data.writeLong(p.ormFlushes());
                    data.writeLong(p.ormDirtyEntities());
                    data.writeBoolean(p.begun());
                    data.writeBoolean(p.boundaryKnown());
                }
                case CONNECTION -> {
                    ConnectionPayload p = (ConnectionPayload) payload;
                    string(p.dataSource());
                    data.writeLong(p.waitNanos());
                    data.writeInt(p.statements());
                    data.writeLong(p.checkoutNanos());
                }
                case MAIL -> {
                    MailPayload p = (MailPayload) payload;
                    data.writeInt(p.recipients());
                    data.writeInt(p.attachments());
                    data.writeBoolean(p.sent());
                }
                case FAULT_TOLERANCE -> {
                    FaultTolerancePayload p = (FaultTolerancePayload) payload;
                    string(p.policy());
                    string(p.policyType());
                    string(p.target());
                    string(p.outcome());
                    optionalInt(p.attempt());
                    string(p.state());
                    string(p.failureCategory());
                    data.writeBoolean(p.failure());
                    data.writeBoolean(p.protective());
                }
                case AI -> {
                    AiPayload p = (AiPayload) payload;
                    string(p.operation());
                    string(p.provider());
                    string(p.model());
                    optionalLong(p.inputTokens());
                    optionalLong(p.outputTokens());
                    string(p.finishReason());
                    data.writeBoolean(p.failed());
                    string(p.spanId());
                    data.writeLong(p.completedNanos());
                    data.writeLong(p.codePathStamp());
                }
                case AUTHORIZATION -> {
                    AuthorizationPayload p = (AuthorizationPayload) payload;
                    string(p.target());
                    string(p.subject());
                    string(p.rule());
                    string(p.authentication());
                    data.writeBoolean(p.granted());
                    data.writeInt(p.authorities());
                }
                case ORM -> {
                    OrmPayload p = (OrmPayload) payload;
                    string(p.persistenceUnit());
                    data.writeInt(p.statements());
                    data.writeLong(p.statementNanos());
                    data.writeInt(p.connectionAcquisitions());
                    data.writeLong(p.acquisitionNanos());
                    data.writeInt(p.flushes());
                    data.writeLong(p.flushNanos());
                    data.writeInt(p.partialFlushes());
                    data.writeLong(p.partialFlushNanos());
                    data.writeInt(p.dirtyEntities());
                    data.writeInt(p.entitiesInContext());
                    data.writeInt(p.l2Hits());
                    data.writeInt(p.l2Misses());
                    data.writeInt(p.l2Puts());
                    data.writeInt(p.flushTimeline().size());
                    for (OrmPayload.Flush flush : p.flushTimeline()) {
                        data.writeLong(flush.offsetNanos());
                        data.writeLong(flush.durationNanos());
                        data.writeBoolean(flush.auto());
                        data.writeInt(flush.entities());
                    }
                }
                case GC -> {
                    GcPayload p = (GcPayload) payload;
                    string(p.collector());
                    data.writeLong(p.gcId());
                    string(p.action());
                    string(p.cause());
                    data.writeBoolean(p.pause());
                    data.writeLong(p.heapBeforeBytes());
                    data.writeLong(p.heapAfterBytes());
                    data.writeLong(p.oldGenBeforeBytes());
                    data.writeLong(p.oldGenAfterBytes());
                }
                case LOG -> {
                    LogPayload p = (LogPayload) payload;
                    string(p.logger());
                    string(p.level());
                    string(p.template());
                    string(p.exceptionClass());
                }
                case APP_EVENT -> {
                    AppEventPayload p = (AppEventPayload) payload;
                    string(p.kind());
                    string(p.eventType());
                    string(p.listener());
                    string(p.phase());
                    string(p.outcome());
                    string(p.exceptionClass());
                    data.writeInt(p.listeners());
                    data.writeLong(p.startNanos());
                }
                case LIFECYCLE -> {
                    LifecyclePayload p = (LifecyclePayload) payload;
                    string(p.kind());
                    string(p.target());
                    runStart(p.runStart());
                }
                case WEBSOCKET -> {
                    WebSocketPayload p = (WebSocketPayload) payload;
                    string(p.endpoint());
                    string(p.kind());
                    data.writeBoolean(p.inbound());
                    string(p.destination());
                    data.writeLong(p.payloadBytes());
                    string(p.closeStatus());
                    data.writeBoolean(p.failed());
                }
                case ASYNC_HANDOFF -> {
                    AsyncHandoffPayload p = (AsyncHandoffPayload) payload;
                    string(p.executionId());
                    string(p.parentExecutionId());
                    string(p.taskClass());
                    string(p.hook());
                    data.writeLong(p.submittedEpochMillis());
                    data.writeLong(p.queuedNanos());
                    optionalLong(p.allocatedBytes());
                    data.writeBoolean(p.failed());
                    string(p.exceptionClass());
                    optionalBoolean(p.afterResponse());
                    optionalLong(p.afterResponseMicros());
                    data.writeBoolean(p.capped());
                    optionalBoolean(p.bodyAfterResponse());
                    optionalLong(p.bodyAfterResponseMicros());
                    optionalLong(p.responseAtMicros());
                    optionalBoolean(p.failureAfterResponse());
                }
                case CAUGHT_EXCEPTION -> {
                    CaughtExceptionPayload p = (CaughtExceptionPayload) payload;
                    string(p.kind());
                    string(p.siteClass());
                    string(p.siteMethod());
                    data.writeInt(p.line());
                    strings(p.declaredTypes());
                    data.writeInt(p.siteFlags());
                    string(p.exceptionClass());
                    string(p.family());
                    data.writeLong(p.count());
                    string(p.foundBy());
                    string(p.foundAt());
                    data.writeInt(p.identity());
                }
            }
        }
    }

    private static final class In {

        private final DataInputStream data;

        private In(DataInputStream data) {
            this.data = data;
        }

        private boolean bool() throws IOException {
            int value = data.readUnsignedByte();
            if (value > 1) {
                throw new IllegalArgumentException("Invalid journal boolean: " + value);
            }
            return value == 1;
        }

        private int count(int minimumBytesPerElement) throws IOException {
            int count = data.readInt();
            if (count < 0 || count > data.available() / minimumBytesPerElement) {
                throw new IllegalArgumentException("Invalid journal collection length: " + count);
            }
            return count;
        }

        private String string() throws IOException {
            int length = data.readInt();
            if (length == -1) {
                return null;
            }
            if (length < 0 || length > data.available() / Character.BYTES) {
                throw new IllegalArgumentException("Invalid journal string length: " + length);
            }
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = data.readChar();
            }
            return new String(chars);
        }

        private <E extends Enum<E>> E enumeration(Class<E> type) throws IOException {
            String name = string();
            return name == null ? null : Enum.valueOf(type, name);
        }

        private Long optionalLong() throws IOException {
            return bool() ? data.readLong() : null;
        }

        private Integer optionalInt() throws IOException {
            return bool() ? data.readInt() : null;
        }

        private Boolean optionalBoolean() throws IOException {
            return bool() ? bool() : null;
        }

        private List<String> strings() throws IOException {
            int count = count(Integer.BYTES);
            List<String> strings = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                strings.add(string());
            }
            return List.copyOf(strings);
        }

        private ApplicationFrames frames() throws IOException {
            if (!bool()) {
                return null;
            }
            List<String> frames = strings();
            int unsharedBytes = data.readInt();
            if (unsharedBytes < 0) {
                throw new IllegalArgumentException("Invalid application frames retention size: " + unsharedBytes);
            }
            try {
                return FRAMES_CONSTRUCTOR.newInstance(frames, unsharedBytes);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException("Cannot decode application frames", ex);
            }
        }

        private ResourceUsage resources() throws IOException {
            if (!bool()) {
                return null;
            }
            long cpu = data.readLong();
            long allocated = data.readLong();
            int segments = data.readInt();
            int unmeasured = data.readInt();
            ResourceUsage.Unmeasured reason = enumeration(ResourceUsage.Unmeasured.class);
            long pauses = data.readLong();
            int count = count(Integer.BYTES + 2 * Long.BYTES);
            List<GcPauseRange> ranges = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ranges.add(new GcPauseRange(string(), data.readLong(), data.readLong()));
            }
            return new ResourceUsage(cpu, allocated, segments, unmeasured, reason, pauses, ranges, bool());
        }

        private RequestTiming timing() throws IOException {
            return bool()
                    ? new RequestTiming(data.readLong(), data.readLong(), data.readLong(), data.readLong())
                    : null;
        }

        private RunStart runStart() throws IOException {
            if (!bool()) {
                return null;
            }
            Long ready = optionalLong();
            int count = count(2 * Integer.BYTES + Long.BYTES);
            List<StartupStepTiming> steps = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                steps.add(new StartupStepTiming(string(), string(), data.readLong()));
            }
            List<String> profiles = strings();
            int sources = count(2 * Integer.BYTES);
            Map<String, String> dataSources = new LinkedHashMap<>();
            for (int i = 0; i < sources; i++) {
                String key = string();
                if (dataSources.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate journal data source: " + key);
                }
                dataSources.put(key, string());
            }
            return new RunStart(
                    ready,
                    steps,
                    new ComparabilityFacts(
                            profiles, Collections.unmodifiableMap(dataSources), string(), bool(), strings()));
        }

        private List<OrmPayload.Flush> flushes() throws IOException {
            int count = count(2 * Long.BYTES + 1 + Integer.BYTES);
            List<OrmPayload.Flush> flushes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                flushes.add(new OrmPayload.Flush(data.readLong(), data.readLong(), bool(), data.readInt()));
            }
            return List.copyOf(flushes);
        }

        private RuntimeEventPayload payload() throws IOException {
            int id = data.readInt();
            if (id == 0) {
                return null;
            }
            PayloadType type = PAYLOAD_IDS.get(id);
            if (type == null) {
                throw new IllegalArgumentException("Unknown journal payload type: " + id);
            }
            return switch (type) {
                case HTTP ->
                    new HttpPayload(
                            string(), string(), string(), string(), data.readInt(), resources(), timing(), bool());
                case SQL ->
                    new SqlPayload(
                            string(),
                            string(),
                            string(),
                            bool(),
                            frames(),
                            enumeration(RequestPhase.class),
                            data.readLong(),
                            data.readLong(),
                            enumeration(SqlPayload.Provenance.class),
                            data.readInt(),
                            optionalLong());
                case REST_CLIENT ->
                    new RestClientPayload(
                            string(),
                            string(),
                            string(),
                            optionalInt(),
                            string(),
                            bool(),
                            frames(),
                            data.readLong(),
                            data.readLong());
                case EXCEPTION -> new ExceptionPayload(string(), string(), string(), strings(), null, bool());
                case SECURITY -> new SecurityPayload(string());
                case CACHE -> new CachePayload(string(), string(), frames(), data.readLong());
                case SCHEDULED -> new ScheduledPayload(string(), string());
                case MESSAGING -> new MessagingPayload(string(), bool(), string(), bool(), string());
                case TRANSACTION ->
                    new TransactionPayload(
                            string(),
                            bool(),
                            bool(),
                            bool(),
                            data.readLong(),
                            bool(),
                            string(),
                            bool(),
                            string(),
                            data.readLong(),
                            data.readLong(),
                            data.readLong(),
                            bool(),
                            bool());
                case CONNECTION -> new ConnectionPayload(string(), data.readLong(), data.readInt(), data.readLong());
                case MAIL -> new MailPayload(data.readInt(), data.readInt(), bool());
                case FAULT_TOLERANCE ->
                    new FaultTolerancePayload(
                            string(), string(), string(), string(), optionalInt(), string(), string(), bool(), bool());
                case AI ->
                    new AiPayload(
                            string(),
                            string(),
                            string(),
                            optionalLong(),
                            optionalLong(),
                            string(),
                            bool(),
                            string(),
                            data.readLong(),
                            data.readLong());
                case AUTHORIZATION ->
                    new AuthorizationPayload(string(), string(), string(), string(), bool(), data.readInt());
                case ORM ->
                    new OrmPayload(
                            string(),
                            data.readInt(),
                            data.readLong(),
                            data.readInt(),
                            data.readLong(),
                            data.readInt(),
                            data.readLong(),
                            data.readInt(),
                            data.readLong(),
                            data.readInt(),
                            data.readInt(),
                            data.readInt(),
                            data.readInt(),
                            data.readInt(),
                            flushes());
                case GC ->
                    new GcPayload(
                            string(),
                            data.readLong(),
                            string(),
                            string(),
                            bool(),
                            data.readLong(),
                            data.readLong(),
                            data.readLong(),
                            data.readLong());
                case LOG -> new LogPayload(string(), string(), string(), string(), null);
                case APP_EVENT ->
                    new AppEventPayload(
                            string(),
                            string(),
                            string(),
                            string(),
                            string(),
                            string(),
                            data.readInt(),
                            data.readLong());
                case LIFECYCLE -> new LifecyclePayload(string(), string(), runStart());
                case WEBSOCKET ->
                    new WebSocketPayload(string(), string(), bool(), string(), data.readLong(), string(), bool());
                case ASYNC_HANDOFF ->
                    new AsyncHandoffPayload(
                            string(),
                            string(),
                            string(),
                            string(),
                            data.readLong(),
                            data.readLong(),
                            optionalLong(),
                            bool(),
                            string(),
                            optionalBoolean(),
                            optionalLong(),
                            bool(),
                            optionalBoolean(),
                            optionalLong(),
                            optionalLong(),
                            optionalBoolean());
                case CAUGHT_EXCEPTION ->
                    new CaughtExceptionPayload(
                            string(),
                            string(),
                            string(),
                            data.readInt(),
                            strings(),
                            data.readInt(),
                            string(),
                            string(),
                            data.readLong(),
                            string(),
                            string(),
                            data.readInt());
            };
        }
    }

    private static Constructor<ApplicationFrames> framesConstructor() {
        try {
            Constructor<ApplicationFrames> constructor =
                    ApplicationFrames.class.getDeclaredConstructor(List.class, int.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
