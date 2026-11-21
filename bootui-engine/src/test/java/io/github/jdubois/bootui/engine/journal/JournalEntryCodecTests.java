package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JournalEntryCodecTests {

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("fixtures")
    void roundTripsEveryPayloadAndTheCompleteEnvelope(Fixture fixture) {
        JournalEntry original = entry(fixture.source(), fixture.payload());
        byte[] encoded = JournalEntryCodec.encode(original);

        JournalEntry decoded = JournalEntryCodec.decode(encoded);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.event().payload())
                .isExactlyInstanceOf(fixture.payload().getClass());
        assertThat(JournalEntryCodec.encode(decoded)).containsExactly(encoded);
    }

    @Test
    void fixturesCoverEveryProductionPayloadAndEventSource() {
        Set<String> productionPayloads = new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackagesOf(RuntimeEventPayload.class)
                        .stream()
                        .filter(type -> !type.isInterface() && type.isAssignableTo(RuntimeEventPayload.class))
                        .map(JavaClass::getName)
                        .collect(Collectors.toSet());

        assertThat(fixtures()
                        .map(fixture -> fixture.payload().getClass().getName())
                        .collect(Collectors.toSet()))
                .isEqualTo(productionPayloads);
        assertThat(fixtures().map(Fixture::source).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(JournalSource.values())
                        .filter(JournalSource::publishesEvents)
                        .toList());
    }

    @Test
    void readsAndWritesAFixedVersionOneEntry() {
        byte[] versionOne = HexFormat.of()
                .parseHex("42554a45" + "00000001"
                        + "0000000000000001"
                        + "00000003"
                        + "00000004004d00410049004c"
                        + "0000000000000002"
                        + "ffffffffffffffff"
                        + "ffffffffffffffffffffffffffffffffffffffff"
                        + "00"
                        + "0000000b"
                        + "00000002"
                        + "00000003"
                        + "01");
        JournalEntry expected = new JournalEntry(
                1,
                new RuntimeEvent(
                        JournalSource.MAIL, 2, -1, null, null, null, null, null, false, new MailPayload(2, 3, true)),
                3);

        assertThat(JournalEntryCodec.decode(versionOne)).isEqualTo(expected);
        assertThat(JournalEntryCodec.encode(expected)).containsExactly(versionOne);
    }

    @ParameterizedTest
    @EnumSource(ThreadKind.class)
    @NullSource
    void preservesEveryThreadKindAndAbsentPayloads(ThreadKind threadKind) {
        JournalEntry original = new JournalEntry(
                Long.MAX_VALUE,
                new RuntimeEvent(
                        JournalSource.SQL, Long.MIN_VALUE, -1, null, null, null, null, threadKind, false, null),
                Integer.MIN_VALUE);

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @ParameterizedTest
    @EnumSource(RequestPhase.class)
    @NullSource
    void preservesSqlRequestPhasesAndLiteralTextWithoutMasking(RequestPhase phase) {
        SqlPayload sql = new SqlPayload(
                "select * from accounts where password = 'not-masked-by-this-codec'",
                null,
                "main",
                false,
                frames(),
                phase,
                123,
                456,
                SqlPayload.Provenance.EXECUTION,
                7,
                Long.MAX_VALUE);

        assertThat(roundTrip(entry(JournalSource.SQL, sql)).event().payload()).isEqualTo(sql);
    }

    @ParameterizedTest
    @EnumSource(SqlPayload.Provenance.class)
    void preservesSqlExecutionAndPreparationEvidence(SqlPayload.Provenance provenance) {
        SqlPayload sql = new SqlPayload(
                "update orders set state = 'PAID'",
                "Orders.pay(Orders.java:42)",
                "main",
                false,
                null,
                null,
                -1,
                0,
                provenance,
                0,
                0L);

        assertThat(roundTrip(entry(JournalSource.SQL, sql)).event().payload()).isEqualTo(sql);
    }

    @ParameterizedTest
    @EnumSource(ResourceUsage.Unmeasured.class)
    @NullSource
    void preservesResourceMeasurementAvailability(ResourceUsage.Unmeasured reason) {
        ResourceUsage usage = new ResourceUsage(1, 2, 3, reason == null ? 0 : 1, reason, 4, List.of(), true);
        HttpPayload http = new HttpPayload(
                "GET", "/", "/", null, 200, usage, new RequestTiming(Long.MIN_VALUE, -1, -1, -1), false);

        assertThat(roundTrip(entry(JournalSource.HTTP, http)).event().payload()).isEqualTo(http);
    }

    @Test
    void preservesUnknownRunReadinessAndNullableDataSourceShapes() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("main", "jdbc:postgresql://localhost");
        sources.put("unknown", null);
        RunStart start = new RunStart(
                null,
                List.of(new StartupStepTiming("ready", null, -1)),
                new ComparabilityFacts(List.of(), sources, "none", false, List.of()));
        LifecyclePayload lifecycle = LifecyclePayload.runStarted(start);

        assertThat(roundTrip(entry(JournalSource.LIFECYCLE, lifecycle)).event().payload())
                .isEqualTo(lifecycle);
    }

    @Test
    void preservesAllThreeStatesOfOptionalHandoffBooleansAndBoxedZeroes() {
        for (Boolean flag : Arrays.asList(null, Boolean.FALSE, Boolean.TRUE)) {
            AsyncHandoffPayload handoff = new AsyncHandoffPayload(
                    "async-1", null, null, null, 0, -1, 0L, false, null, flag, 0L, false, flag, 0L, 0L, flag);
            assertThat(roundTrip(entry(JournalSource.AGENT_EXECUTORS, handoff))
                            .event()
                            .payload())
                    .isEqualTo(handoff);
        }
    }

    @Test
    void preservesEmptyUnicodeNulLongStringsAndUnpairedSurrogates() {
        String text = "\0é東京🚀\ud800x\udc00".repeat(10_000);
        JournalEntry original = new JournalEntry(
                0,
                new RuntimeEvent(
                        JournalSource.SECURITY,
                        0,
                        0,
                        "",
                        text,
                        null,
                        text,
                        ThreadKind.OTHER,
                        false,
                        new SecurityPayload(text)),
                0);

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void neverEncodesThrowableMarksButKeepsTheOriginalEstimatedBytesAndAllOtherFields() {
        ThrowableMarks first = ThrowableMarks.ofIdentities(123, 456);
        ThrowableMarks second = ThrowableMarks.ofIdentities(789);
        for (RuntimeEventPayload payload : List.of(
                new ExceptionPayload("group", "Failure", "signature", List.of("Failure", "Cause"), first, true),
                new LogPayload("logger", "ERROR", "failed {}", "Failure", first))) {
            RuntimeEventPayload unmarked = payload instanceof ExceptionPayload p
                    ? new ExceptionPayload(p.groupId(), p.exceptionClass(), p.signature(), p.types(), null, p.logged())
                    : new LogPayload("logger", "ERROR", "failed {}", "Failure", null);
            RuntimeEventPayload otherMarks = payload instanceof ExceptionPayload p
                    ? new ExceptionPayload(
                            p.groupId(), p.exceptionClass(), p.signature(), p.types(), second, p.logged())
                    : new LogPayload("logger", "ERROR", "failed {}", "Failure", second);
            JournalSource source = payload instanceof ExceptionPayload ? JournalSource.EXCEPTION : JournalSource.LOG;
            JournalEntry original = entry(source, payload);
            byte[] encoded = JournalEntryCodec.encode(original);

            assertThat(encoded).containsExactly(JournalEntryCodec.encode(entry(source, unmarked)));
            assertThat(encoded).containsExactly(JournalEntryCodec.encode(entry(source, otherMarks)));
            assertThat(roundTrip(original)).isEqualTo(entry(source, unmarked));
            assertThat(payload instanceof ExceptionPayload p ? p.marks() : ((LogPayload) payload).marks())
                    .isSameAs(first);
        }
    }

    @Test
    void preservesEmptyUnsharedPartlyInternedAndFullyInternedApplicationFrames() {
        ApplicationFrames original = frames();
        ApplicationFrames partial = original.interned(new JournalDictionary(1, Long.MAX_VALUE));
        ApplicationFrames shared = original.interned(new JournalDictionary(10, Long.MAX_VALUE));
        assertThat(partial.unsharedBytes()).isStrictlyBetween(0, original.unsharedBytes());
        assertThat(shared.unsharedBytes()).isZero();

        for (ApplicationFrames frames : List.of(original, partial, shared, ApplicationFrames.of(List.of()))) {
            CachePayload payload = new CachePayload("cache", "get", frames, 123);
            CachePayload decoded = (CachePayload)
                    roundTrip(entry(JournalSource.CACHE, payload)).event().payload();

            assertThat(decoded).isEqualTo(payload);
            assertThat(decoded.frames().unsharedBytes()).isEqualTo(frames.unsharedBytes());
            assertThat(decoded.frames().callSite()).isEqualTo(frames.callSite());
            assertThatThrownBy(() -> decoded.frames().frames().add("other"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void formatsLazyApplicationFramesWithoutPersistingStackWalkerObjects() {
        StackWalker.StackFrame frame = mock(StackWalker.StackFrame.class);
        when(frame.getClassName()).thenReturn("com.example.Service");
        when(frame.getMethodName()).thenReturn("work");
        when(frame.getFileName()).thenReturn("Service.java");
        when(frame.getLineNumber()).thenReturn(42);
        ApplicationFrames lazy = ApplicationFrames.select(Stream.of(frame));
        assertThat(lazy.isFormatted()).isFalse();
        CachePayload payload = new CachePayload("cache", "get", lazy, 123);

        CachePayload decoded = (CachePayload)
                roundTrip(entry(JournalSource.CACHE, payload)).event().payload();

        assertThat(decoded).isEqualTo(payload);
        assertThat(decoded.frames().isFormatted()).isTrue();
        assertThat(decoded.frames().frames()).containsExactly("com.example.Service.work(Service.java:42)");
    }

    @Test
    void decodedNestedCollectionsAreImmutable() {
        for (Fixture fixture : fixtures().toList()) {
            RuntimeEventPayload decoded = roundTrip(entry(fixture.source(), fixture.payload()))
                    .event()
                    .payload();
            if (decoded instanceof HttpPayload p && p.resources() != null) {
                assertThatThrownBy(() -> p.resources().gcPauseRanges().clear())
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            if (decoded instanceof OrmPayload p) {
                assertThatThrownBy(() -> p.flushTimeline().add(new OrmPayload.Flush(0, 0, false, 0)))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            if (decoded instanceof ExceptionPayload p) {
                assertThatThrownBy(() -> p.types().add("Other")).isInstanceOf(UnsupportedOperationException.class);
            }
            if (decoded instanceof CaughtExceptionPayload p) {
                assertThatThrownBy(() -> p.declaredTypes().add("Other"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            if (decoded instanceof LifecyclePayload p && p.runStart() != null) {
                RunStart start = p.runStart();
                assertThatThrownBy(() -> start.slowestSteps().add(new StartupStepTiming("other", null, 0)))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> start.facts().activeProfiles().add("other"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> start.facts().journalSources().add("other"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> start.facts().dataSources().put("other", "shape"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("fixtures")
    void rejectsEveryTruncatedPrefix(Fixture fixture) {
        byte[] encoded = JournalEntryCodec.encode(entry(fixture.source(), fixture.payload()));
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> JournalEntryCodec.decode(truncated))
                    .as("prefix of length %d for %s", length, fixture)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnknownPayloadImplementationsRatherThanDroppingTheirFields() {
        RuntimeEventPayload unknown = () -> 123;

        assertThatThrownBy(() -> JournalEntryCodec.encode(entry(JournalSource.SQL, unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported journal payload class");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 23, Integer.MAX_VALUE})
    void rejectsUnknownPayloadIds(int id) throws IOException {
        assertThatThrownBy(() -> JournalEntryCodec.decode(payloadBytes(id, out -> {})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown journal payload type");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void rejectsUnknownVersions(int version) {
        byte[] encoded = JournalEntryCodec.encode(entry(JournalSource.SQL, null));
        ByteBuffer.wrap(encoded).putInt(4, version);

        assertThatThrownBy(() -> JournalEntryCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codec version");
    }

    @Test
    void rejectsInvalidMagicAndTrailingBytes() {
        byte[] encoded = JournalEntryCodec.encode(entry(JournalSource.SQL, null));
        byte[] badMagic = encoded.clone();
        badMagic[0] = 0;

        assertThatThrownBy(() -> JournalEntryCodec.decode(badMagic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");
        assertThatThrownBy(() -> JournalEntryCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Trailing data");
    }

    @Test
    void rejectsUnknownEnumNamesAndSourcesThatCannotPublish() {
        byte[] unknownSource = JournalEntryCodec.encode(entry(JournalSource.SQL, null));
        // Header (8), sequence (8), retained bytes (4), then source character count (4).
        ByteBuffer.wrap(unknownSource).putChar(24, 'X');
        assertThatThrownBy(() -> JournalEntryCodec.decode(unknownSource)).isInstanceOf(IllegalArgumentException.class);

        byte[] resourcesSource = JournalEntryCodec.encode(entry(JournalSource.SCHEDULED, null));
        ByteBuffer source = ByteBuffer.wrap(resourcesSource);
        for (int i = 0; i < "RESOURCES".length(); i++) {
            source.putChar(24 + Character.BYTES * i, "RESOURCES".charAt(i));
        }
        assertThatThrownBy(() -> JournalEntryCodec.decode(resourcesSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publishes no events");
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsInvalidStringLengthsBeforeAllocating(int length) throws IOException {
        byte[] encoded = payloadBytes(5, out -> out.writeInt(length));

        assertThatThrownBy(() -> JournalEntryCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string length");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsInvalidCollectionLengthsBeforeAllocating(int count) throws IOException {
        byte[] encoded = payloadBytes(4, out -> {
            writeStrings(out, null, null, null);
            out.writeInt(count);
        });

        assertThatThrownBy(() -> JournalEntryCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection length");
    }

    @Test
    void rejectsInvalidBooleansAndOptionalValueMarkers() throws IOException {
        byte[] mail = payloadBytes(11, out -> {
            out.writeInt(1);
            out.writeInt(2);
            out.writeByte(2);
        });
        byte[] rest = payloadBytes(3, out -> {
            writeStrings(out, null, null, null);
            out.writeByte(255);
        });

        for (byte[] bytes : List.of(mail, rest)) {
            assertThatThrownBy(() -> JournalEntryCodec.decode(bytes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("boolean");
        }
    }

    @Test
    void rejectsInvalidNestedGcRangesAndRequiredFields() throws IOException {
        byte[] gcRange = payloadBytes(1, out -> {
            writeStrings(out, "GET", "/", "/", null);
            out.writeInt(200);
            out.writeBoolean(true);
            out.writeLong(1);
            out.writeLong(2);
            out.writeInt(1);
            out.writeInt(0);
            writeStrings(out, (String) null);
            out.writeLong(1);
            out.writeInt(1);
            writeStrings(out, "collector");
            out.writeLong(2);
            out.writeLong(1);
            out.writeBoolean(false);
            out.writeBoolean(false);
            out.writeBoolean(false);
        });
        byte[] lifecycle = payloadBytes(19, out -> {
            writeStrings(out, null, null);
            out.writeBoolean(false);
        });

        assertThatThrownBy(() -> JournalEntryCodec.decode(gcRange))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
        assertThatThrownBy(() -> JournalEntryCodec.decode(lifecycle)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullEntryEventAndEncodedBytes() {
        assertThatThrownBy(() -> JournalEntryCodec.encode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntryCodec.encode(new JournalEntry(1, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntryCodec.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JournalEntry entry(JournalSource source, RuntimeEventPayload payload) {
        return new JournalEntry(
                9_876_543_210L,
                new RuntimeEvent(
                        source,
                        1_731_234_567_890L,
                        123_456_789L,
                        "request-1",
                        "execution-2",
                        "trace-3",
                        "worker-é",
                        ThreadKind.WORKER,
                        true,
                        payload),
                12_345);
    }

    private static JournalEntry roundTrip(JournalEntry entry) {
        return JournalEntryCodec.decode(JournalEntryCodec.encode(entry));
    }

    private static ApplicationFrames frames() {
        return ApplicationFrames.of(List.of(
                "com.example.Repository.find(Repository.java:12)",
                "com.example.Service.work(Service.java:34)",
                "com.example.Controller.get(Controller.java:56)"));
    }

    private static Stream<Fixture> fixtures() {
        ApplicationFrames frames = frames();
        ResourceUsage resources = new ResourceUsage(
                101,
                202,
                3,
                1,
                ResourceUsage.Unmeasured.THREAD_ENDED,
                4,
                List.of(new GcPauseRange("young", 10, 12), new GcPauseRange("old", 20, 22)),
                true);
        RunStart start = new RunStart(
                123_456L,
                List.of(new StartupStepTiming("instantiate", "service", 234), new StartupStepTiming("ready", null, 56)),
                new ComparabilityFacts(
                        List.of("test", "local"),
                        Map.of("main", "jdbc:h2:mem", "other", "jdbc:postgresql://localhost"),
                        "Caffeine",
                        true,
                        List.of("http", "sql")));
        return Stream.of(
                new Fixture(
                        JournalSource.HTTP,
                        new HttpPayload(
                                "POST",
                                "/orders/42",
                                "/orders/{id}",
                                "save",
                                503,
                                resources,
                                new RequestTiming(-123, 234, 345, 456),
                                true)),
                new Fixture(JournalSource.HTTP, new HttpPayload(null, null, null, null, 0, null, null, false)),
                new Fixture(
                        JournalSource.SQL,
                        new SqlPayload(
                                "select * from orders where id = 42",
                                "explicit.site(Site.java:7)",
                                "main",
                                false,
                                frames,
                                RequestPhase.RESPONSE,
                                123,
                                234,
                                SqlPayload.Provenance.EXECUTION,
                                3,
                                5L)),
                new Fixture(
                        JournalSource.SQL,
                        new SqlPayload(
                                null, null, null, true, null, null, -1, 0, SqlPayload.Provenance.UNKNOWN, 0, null)),
                new Fixture(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload("GET", "remote:8443", "/orders", 502, "jdk", true, frames, 345, 456)),
                new Fixture(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload(null, null, null, null, null, false, null, -1, 0)),
                new Fixture(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "group",
                                "Failure",
                                "signature",
                                List.of("Failure", "Cause", "Suppressed"),
                                null,
                                true)),
                new Fixture(JournalSource.EXCEPTION, new ExceptionPayload(null, null, null, List.of(), null, false)),
                new Fixture(JournalSource.SECURITY, new SecurityPayload("AUTHENTICATION_FAILURE")),
                new Fixture(JournalSource.SECURITY, new SecurityPayload(null)),
                new Fixture(JournalSource.CACHE, new CachePayload("orders", "get", frames, 567)),
                new Fixture(JournalSource.CACHE, new CachePayload(null, null, null, 0)),
                new Fixture(JournalSource.SCHEDULED, new ScheduledPayload("Orders.refresh", "Failure")),
                new Fixture(JournalSource.SCHEDULED, new ScheduledPayload(null, null)),
                new Fixture(
                        JournalSource.MESSAGING, new MessagingPayload("kafka", true, "orders", true, "linked-trace")),
                new Fixture(JournalSource.MESSAGING, new MessagingPayload(null, false, null, false, null)),
                new Fixture(
                        JournalSource.TRANSACTION,
                        new TransactionPayload(
                                "Orders.save",
                                true,
                                true,
                                true,
                                678,
                                true,
                                "SERIALIZABLE",
                                true,
                                "Failure",
                                12,
                                23,
                                34,
                                true,
                                true)),
                new Fixture(
                        JournalSource.TRANSACTION,
                        new TransactionPayload(
                                null, false, false, false, -1, false, null, false, null, -1, -1, -1, false, false)),
                new Fixture(JournalSource.CONNECTION, new ConnectionPayload("main", 789, 8, 890)),
                new Fixture(JournalSource.CONNECTION, new ConnectionPayload(null, -1, 0, -1)),
                new Fixture(JournalSource.MAIL, new MailPayload(3, 4, true)),
                new Fixture(JournalSource.MAIL, new MailPayload(0, 0, false)),
                new Fixture(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(
                                "remote", "retry", "Orders.fetch", "failed", 3, "open", "timeout", true, true)),
                new Fixture(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(null, null, null, null, null, null, null, false, false)),
                new Fixture(
                        JournalSource.AI,
                        new AiPayload("chat", "provider", "model", 12L, 23L, "stop", true, "span-4", 901, 1_012)),
                new Fixture(JournalSource.AI, new AiPayload(null, null, null, null, null, null, false, null, -1, 0)),
                new Fixture(
                        JournalSource.AUTHORIZATION,
                        new AuthorizationPayload("request", "/orders", "hasRole", "authenticated", true, 3)),
                new Fixture(
                        JournalSource.AUTHORIZATION, new AuthorizationPayload("method", null, null, null, false, 0)),
                new Fixture(
                        JournalSource.ORM,
                        new OrmPayload(
                                "main",
                                1,
                                2,
                                3,
                                4,
                                5,
                                6,
                                7,
                                8,
                                9,
                                10,
                                11,
                                12,
                                13,
                                List.of(
                                        new OrmPayload.Flush(14, 15, true, 16),
                                        new OrmPayload.Flush(17, 18, false, 19)))),
                new Fixture(
                        JournalSource.ORM, new OrmPayload(null, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, 0, 0, List.of())),
                new Fixture(JournalSource.GC, new GcPayload("young", 12, "pause", "allocation", true, 23, 34, 45, 56)),
                new Fixture(JournalSource.GC, new GcPayload(null, -1, null, null, false, -1, -1, -1, -1)),
                new Fixture(JournalSource.LOG, new LogPayload("Orders", "ERROR", "Failed for {}", "Failure", null)),
                new Fixture(JournalSource.LOG, new LogPayload(null, null, null, null, null)),
                new Fixture(
                        JournalSource.APP_EVENT,
                        new AppEventPayload(
                                "LISTENER",
                                "OrderPlaced",
                                "Orders.listen",
                                "AFTER_COMMIT",
                                "failed",
                                "Failure",
                                3,
                                1_123)),
                new Fixture(
                        JournalSource.APP_EVENT, new AppEventPayload("PUBLISHED", null, null, null, null, null, 0, -1)),
                new Fixture(JournalSource.LIFECYCLE, LifecyclePayload.runStarted(start)),
                new Fixture(JournalSource.LIFECYCLE, new LifecyclePayload(LifecyclePayload.ACTION, "cache", null)),
                new Fixture(
                        JournalSource.WEBSOCKET,
                        new WebSocketPayload("/socket", "message", true, "/topic/orders", 2_345L, "1001", true)),
                new Fixture(JournalSource.WEBSOCKET, new WebSocketPayload(null, null, false, null, -1, null, false)),
                new Fixture(
                        JournalSource.AGENT_EXECUTORS,
                        new AsyncHandoffPayload(
                                "async-1",
                                "async-parent",
                                "OrdersTask",
                                "runWorker",
                                3_456,
                                4_567,
                                5_678L,
                                true,
                                "Failure",
                                true,
                                6_789L,
                                true,
                                false,
                                0L,
                                7_890L,
                                true)),
                new Fixture(
                        JournalSource.AGENT_EXECUTORS,
                        new AsyncHandoffPayload(
                                null, null, null, null, -1, -1, null, false, null, null, null, false, null, null, null,
                                null)),
                new Fixture(
                        JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                        new CaughtExceptionPayload(
                                "CAUGHT",
                                "Orders",
                                "save",
                                42,
                                List.of("Failure", "OtherFailure"),
                                7,
                                "ConcreteFailure",
                                "io",
                                123,
                                "LOG",
                                "Service.log",
                                456)),
                new Fixture(
                        JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                        new CaughtExceptionPayload(null, null, null, -1, List.of(), 0, null, null, 0, null, null, 0)));
    }

    private record Fixture(JournalSource source, RuntimeEventPayload payload) {
        @Override
        public String toString() {
            return payload.getClass().getSimpleName();
        }
    }

    private static byte[] payloadBytes(int id, PayloadWriter writer) throws IOException {
        byte[] empty = JournalEntryCodec.encode(entry(JournalSource.SQL, null));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(empty, 0, empty.length - Integer.BYTES);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(id);
        writer.write(out);
        return bytes.toByteArray();
    }

    private static void writeStrings(DataOutputStream out, String... strings) throws IOException {
        for (String string : strings) {
            out.writeInt(string == null ? -1 : string.length());
            if (string != null) {
                for (int i = 0; i < string.length(); i++) {
                    out.writeChar(string.charAt(i));
                }
            }
        }
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
