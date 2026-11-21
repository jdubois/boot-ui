package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RunStats;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableJournalRunMetadataCodecTests {

    @Test
    void roundTripsCurrentMetadataAndEveryStatusCounterAndSource() {
        DurableJournalRunMetadata metadata = metadata(DurableJournalRunMetadata.Phase.CHECKPOINT, summary());

        DurableJournalRunMetadata decoded =
                DurableJournalRunMetadataCodec.decode(DurableJournalRunMetadataCodec.encode(metadata));

        assertThat(decoded.phase()).isEqualTo(DurableJournalRunMetadata.Phase.CHECKPOINT);
        assertThat(decoded.capturedAtEpochMillis()).isEqualTo(20_000);
        assertThat(decoded.instanceId()).isEqualTo("instance-1");
        assertThat(decoded.processId()).isEqualTo("12345");
        assertThat(decoded.stack()).isEqualTo(InsightsStack.SPRING_WEBFLUX);
        assertThat(decoded.stackName()).isEqualTo(InsightsStack.SPRING_WEBFLUX.name());
        assertThat(decoded.journalSettings()).isEqualTo(metadata.journalSettings());
        assertThat(decoded.journalSettings().sources()).isEqualTo(JournalSource.all());
        assertThat(decoded.journalStatus()).isEqualTo(metadata.journalStatus());
        assertThat(decoded.lossHorizonMillis()).isEqualTo(10_000);
        assertThat(decoded.lostRequestEndMillis()).isEqualTo(10_250);
        assertThat(decoded.lostRequestTraces()).containsExactly("trace-latest", "trace-next");
        assertThat(decoded.lostRequestThreads()).containsExactly("http-1", "worker-3");
        assertThat(decoded.lostBySource()).isEqualTo(metadata.lostBySource());
        assertThat(decoded.sqlCaptureQualification()).isEqualTo(metadata.sqlCaptureQualification());
        assertThat(decoded.poolSizes()).isEqualTo(Map.of("orders", 10, "secondary", -1));
        assertThat(decoded.declaredRoutes())
                .isEqualTo(new DurableJournalRunMetadata.DeclaredRoutes(2, true, "one mapping was unavailable"));
        assertThat(decoded.runSummary()).isNotNull();
        assertThat(decoded.runSummary().header().runId()).isEqualTo("run-1");
        assertThat(decoded.runSummary().aggregates())
                .isEqualTo(metadata.runSummary().aggregates());
    }

    @Test
    void supportsStartMissingAndPartialCheckpointBeforeAnEndSummary() {
        for (DurableJournalRunMetadata.Phase phase :
                List.of(DurableJournalRunMetadata.Phase.START, DurableJournalRunMetadata.Phase.CHECKPOINT)) {
            DurableJournalRunMetadata decoded =
                    DurableJournalRunMetadataCodec.decode(DurableJournalRunMetadataCodec.encode(metadata(phase, null)));

            assertThat(decoded.phase()).isEqualTo(phase);
            assertThat(decoded.runSummary()).isNull();
        }

        DurableJournalRunMetadata ended = DurableJournalRunMetadataCodec.decode(
                DurableJournalRunMetadataCodec.encode(metadata(DurableJournalRunMetadata.Phase.END, summary())));

        assertThat(ended.phase()).isEqualTo(DurableJournalRunMetadata.Phase.END);
        assertThat(ended.runSummary()).isNotNull();
    }

    @Test
    void defensivelyCopiesNestedListsMapsAndSourceSet() {
        List<String> traces = new ArrayList<>(List.of("trace-1"));
        List<String> threads = new ArrayList<>(List.of("thread-1"));
        EnumMap<JournalSource, Long> lost = new EnumMap<>(JournalSource.class);
        lost.put(JournalSource.HTTP, 123L);
        Map<String, Integer> pools = new LinkedHashMap<>();
        pools.put("pool", 4);
        EnumSet<JournalSource> sources = EnumSet.of(JournalSource.HTTP);

        DurableJournalRunMetadata copy = new DurableJournalRunMetadata(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                1,
                "instance",
                "process",
                InsightsStack.QUARKUS,
                new RuntimeJournalSettings(true, 10, 10_000, 5, 10, 10, sources),
                status(),
                1L,
                2L,
                traces,
                threads,
                lost,
                null,
                pools,
                null,
                null);
        traces.add("trace-2");
        threads.clear();
        lost.clear();
        pools.put("another", 8);
        sources.add(JournalSource.SQL);

        assertThat(copy.lostRequestTraces()).containsExactly("trace-1");
        assertThat(copy.lostRequestThreads()).containsExactly("thread-1");
        assertThat(copy.lostBySource()).containsExactlyEntriesOf(Map.of(JournalSource.HTTP, 123L));
        assertThat(copy.poolSizes()).containsExactlyEntriesOf(Map.of("pool", 4));
        assertThat(copy.journalSettings().sources()).containsExactly(JournalSource.HTTP);
        assertThatThrownBy(() -> copy.poolSizes().put("mutation", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copy.lostRequestTraces().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownVersionTruncationTrailingDataAndMalformedSizes() {
        byte[] encoded = DurableJournalRunMetadataCodec.encode(metadata(DurableJournalRunMetadata.Phase.START, null));

        byte[] unknownVersion = Arrays.copyOf(encoded, encoded.length);
        ByteBuffer.wrap(unknownVersion).putInt(4, DurableJournalRunMetadataCodec.VERSION + 1);
        assertThatThrownBy(() -> DurableJournalRunMetadataCodec.decode(unknownVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> DurableJournalRunMetadataCodec.decode(truncated))
                    .as("truncated at %s", length)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> DurableJournalRunMetadataCodec.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Trailing");

        byte[] badStringLength = Arrays.copyOf(encoded, encoded.length);
        ByteBuffer.wrap(badStringLength).putInt(17, DurableJournalRunMetadata.MAX_IDENTIFIER_BYTES + 1);
        assertThatThrownBy(() -> DurableJournalRunMetadataCodec.decode(badStringLength))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte length");
    }

    @Test
    void neverPersistsRawSqlFeederNamesAndRejectsUnboundedValues() {
        SqlCaptureScopes scopes = new SqlCaptureScopes();
        String rawName = "orders.customer.password-hint-" + "x".repeat(20);
        scopes.register(rawName, SqlPayload.Provenance.EXECUTION);
        DurableJournalRunMetadata.SqlCaptureQualification qualification =
                DurableJournalRunMetadata.SqlCaptureQualification.of(scopes.snapshot());
        DurableJournalRunMetadata metadata = withQualification(qualification);

        byte[] encoded = DurableJournalRunMetadataCodec.encode(metadata);

        assertThat(new String(encoded, StandardCharsets.ISO_8859_1)).doesNotContain(rawName);
        assertThat(DurableJournalRunMetadataCodec.decode(encoded).sqlCaptureQualification())
                .isEqualTo(qualification);
        assertThatThrownBy(() -> new DurableJournalRunMetadata(
                        DurableJournalRunMetadata.Phase.CHECKPOINT,
                        1,
                        "instance",
                        "process",
                        InsightsStack.SPRING_MVC,
                        metadata.journalSettings(),
                        metadata.journalStatus(),
                        null,
                        null,
                        List.of("x".repeat(129)),
                        List.of(),
                        Map.of(),
                        qualification,
                        Map.of(),
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DurableJournalRunMetadata withQualification(
            DurableJournalRunMetadata.SqlCaptureQualification qualification) {
        DurableJournalRunMetadata value = metadata(DurableJournalRunMetadata.Phase.CHECKPOINT, null);
        return new DurableJournalRunMetadata(
                value.phase(),
                value.capturedAtEpochMillis(),
                value.instanceId(),
                value.processId(),
                value.stack(),
                value.stackName(),
                value.journalSettings(),
                value.journalStatus(),
                value.lossHorizonMillis(),
                value.lostRequestEndMillis(),
                value.lostRequestTraces(),
                value.lostRequestThreads(),
                value.lostBySource(),
                qualification,
                value.poolSizes(),
                value.declaredRoutes(),
                null);
    }

    private static DurableJournalRunMetadata metadata(DurableJournalRunMetadata.Phase phase, RunSummary summary) {
        return new DurableJournalRunMetadata(
                phase,
                20_000,
                "instance-1",
                "12345",
                InsightsStack.SPRING_WEBFLUX,
                new RuntimeJournalSettings(true, 100, 1_000_000, 50, 10, 10, JournalSource.all()),
                status(),
                10_000L,
                10_250L,
                List.of("trace-latest", "trace-next"),
                List.of("http-1", "worker-3"),
                Map.of(JournalSource.HTTP, 9_000L, JournalSource.SQL, 9_500L),
                new DurableJournalRunMetadata.SqlCaptureQualification(1, 1, false, "a".repeat(64), "b".repeat(64)),
                Map.of("orders", 10, "secondary", -1),
                new DurableJournalRunMetadata.DeclaredRoutes(2, true, "one mapping was unavailable"),
                summary);
    }

    private static JournalStatus status() {
        Map<JournalSource, Long> accepted = new EnumMap<>(JournalSource.class);
        Map<JournalSource, Long> dropped = new EnumMap<>(JournalSource.class);
        for (JournalSource source : JournalSource.values()) {
            accepted.put(source, source.ordinal() + 10L);
            dropped.put(source, (long) source.ordinal());
        }
        return new JournalStatus(
                true,
                "instance-1",
                "run-1",
                999,
                12,
                3_456,
                8,
                2_000,
                100,
                10_000,
                2,
                10,
                4,
                5,
                "BYTES",
                8_000L,
                3,
                50,
                accepted,
                dropped,
                6,
                7,
                11,
                42);
    }

    private static RunSummary summary() {
        RunIdentity run = new RunIdentity("run-1", 1, 1_000);
        AggregatesSnapshot aggregates = new AggregatesSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new RunStats(
                        Map.of(JournalSource.HTTP, 1L), Map.of(JournalSource.HTTP, 5L), 1_000L, 2_000L, 1, 0, 0, 0),
                Map.of());
        return RunSummary.of(run, "dev:orders", aggregates, null, null, 2_000);
    }
}
