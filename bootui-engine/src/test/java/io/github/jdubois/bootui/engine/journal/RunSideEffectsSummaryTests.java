package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangeDto;
import io.github.jdubois.bootui.engine.codepaths.MethodRoutes;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsAgentView;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M5-7b: a run's side effects travel in its summary, through the run history and the baseline file, so the next run,
 * even after a full JVM restart, says which hosts, files, processes, and variable names are new or gone.
 */
class RunSideEffectsSummaryTests {

    private static final RuntimeJournalSettings SETTINGS =
            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all());

    @TempDir
    Path directory;

    private final RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
    private long sequence;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void sideEffectsRoundTripThroughTheSummaryAndAnOlderSummaryReadsWithNone() {
        RunSideEffects sideEffects = new RunSideEffects(
                null,
                true,
                List.of(
                        new RunSideEffects.Sensor("network", null, null, 0),
                        new RunSideEffects.Sensor("files", "the recording was cleared during the run", "x", 2)),
                List.of(new RunSideEffects.Key(
                        "network", "connect", "api.example.com:443", "route", "GET /orders", "JDK HttpClient", 4)));
        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), null, sideEffects, 2),
                RunHistory.MAX_SUMMARY_BYTES);

        assertThat(encoded[4]).isEqualTo((byte) 13);
        assertThat(RunSummaryCodec.decode(encoded).sideEffects()).isEqualTo(sideEffects);

        byte[] none = RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 2),
                RunHistory.MAX_SUMMARY_BYTES);
        assertThat(RunSummaryCodec.decode(none).sideEffects()).isNull();
        // A version 11 summary, written before side effects, has none: never an empty, comparable set.
        byte[] older = LegacyRunSummaries.asVersion(java.util.Arrays.copyOf(none, none.length - 1), 11);
        assertThat(RunSummaryCodec.decode(older).sideEffects()).isNull();
        assertThat(RunSummaryCodec.decode(older).aggregates().overflowed())
                .doesNotContainKey(JournalAggregates.LEGACY_TABLE_EDGES);
    }

    @Test
    void sideEffectsBeyondTheirOwnBudgetKeepEachSensorsMostFrequentKeysAndCountTheRest() {
        List<RunSideEffects.Key> keys = new ArrayList<>();
        for (int i = 0; i < RunSideEffects.MAX_KEYS_PER_SENSOR; i++) {
            for (String sensor : List.of("network", "files", "processes", "environment")) {
                keys.add(new RunSideEffects.Key(
                        sensor,
                        "read",
                        "/var/data/" + "x".repeat(150) + "/" + sensor + "-" + i,
                        "route",
                        "GET /route-" + i,
                        null,
                        1_000 - i));
            }
        }
        List<RunSideEffects.Sensor> sensors = List.of(
                new RunSideEffects.Sensor("network", null, null, 0),
                new RunSideEffects.Sensor("files", null, null, 0),
                new RunSideEffects.Sensor("processes", null, null, 3),
                new RunSideEffects.Sensor("environment", null, null, 0));
        RunSideEffects full = new RunSideEffects(null, false, sensors, keys);
        assertThat(RunSummaryCodec.sideEffectsBytes(full)).isGreaterThan(RunSummaryCodec.SIDE_EFFECTS_MAX_BYTES);

        RunSideEffects fitted = RunSummaryCodec.fitSideEffects(full, RunSummaryCodec.SIDE_EFFECTS_MAX_BYTES);

        assertThat(RunSummaryCodec.sideEffectsBytes(fitted))
                .isLessThanOrEqualTo(RunSummaryCodec.SIDE_EFFECTS_MAX_BYTES);
        int kept = (int) fitted.keys().stream()
                .filter(key -> key.sensor().equals("network"))
                .count();
        assertThat(kept).isPositive().isLessThan(RunSideEffects.MAX_KEYS_PER_SENSOR);
        assertThat(fitted.keys())
                .filteredOn(key -> key.sensor().equals("network"))
                .extracting(RunSideEffects.Key::count)
                .contains(1_000L);
        assertThat(fitted.sensor("network").omittedKeys()).isEqualTo(RunSideEffects.MAX_KEYS_PER_SENSOR - kept);
        assertThat(fitted.sensor("processes").omittedKeys()).isEqualTo(3L + RunSideEffects.MAX_KEYS_PER_SENSOR - kept);

        RunSummary decoded = RunSummaryCodec.decode(RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), null, full, 2),
                RunHistory.MAX_SUMMARY_BYTES));
        assertThat(decoded.sideEffects()).isEqualTo(fitted);
        assertThat(decoded.header().omittedEntries())
                .as("side effects never count as the aggregates' left-out entries")
                .isZero();
    }

    @Test
    void aBaselineFileOfTheFormatBeforeSideEffectsIsIgnoredWithItsReason() throws IOException {
        Path path = directory.resolve("baseline.bin");
        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 2),
                RunHistory.MAX_SUMMARY_BYTES);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x42554246);
            out.writeByte(1);
            out.writeUTF("2.0.0");
            out.writeUTF("shop");
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        Files.write(path, bytes.toByteArray());

        RunBaselineFile.Read read = new RunBaselineFile(path, "shop", "2.0.0").read();

        assertThat(read.summary()).isNull();
        assertThat(read.ignoredReason())
                .contains("written in baseline format 1, and this BootUI reads format 2, which adds side effects");
    }

    @Test
    void aRestartWithABaselineFileSeesTheNewHostOfARouteBothRunsServed() throws IOException {
        Path path = directory.resolve("baseline.bin");
        RunBaselineFile baseline = new RunBaselineFile(path, "shop");
        JournalAggregates previousAggregates = new JournalAggregates();
        publish(previousAggregates, http("r1", "/orders"));
        baseline.write(RunSummary.of(
                RunIdentity.start(),
                previousAggregates.snapshot(),
                null,
                run(key("network", "connect", "old.example.com:443", "GET /orders")),
                2));

        // A full JVM restart: a new history that keeps nothing reads the file as the previous run.
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        history.loadBaseline(baseline);
        JournalAggregates aggregates = new JournalAggregates();
        publish(aggregates, http("r2", "/orders"));
        RunComparisonService service = new RunComparisonService(journal, aggregates, history);
        service.setCodeChanges(() -> true, limit -> null, wanted -> MethodRoutes.unavailable("not in this test"));
        RunSideEffects current = run(key("network", "connect", "api.example.com:443", "GET /orders"));
        service.setSideEffectsView(() -> new RunComparisonService.SideEffectsView(
                new AgentEvidence.Read("side-effects", true, true, null), null, () -> current));

        RuntimeRunComparisonDto comparison = service.compare(null);

        assertThat(history.baselineNote()).contains("read from the baseline file");
        assertThat(comparison.sideEffects().available()).isTrue();
        assertThat(comparison.sideEffects().changes())
                .extracting(RuntimeSideEffectChangeDto::change, RuntimeSideEffectChangeDto::sentence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                RuntimeSideEffectChangeDto.ADDED,
                                "`GET /orders` now connects to `api.example.com:443`."),
                        org.assertj.core.groups.Tuple.tuple(
                                RuntimeSideEffectChangeDto.REMOVED,
                                "`GET /orders` no longer connects to `old.example.com:443`."));
        RuntimeRunComparisonAgentDto agent = RuntimeInsightsAgentView.comparison(comparison);
        assertThat(agent.sideEffects().changes()).hasSize(2);
        assertThat(agent.next()).anySatisfy(next -> assertThat(next.tool()).isEqualTo("get_side_effects"));
    }

    @Test
    void withoutTheAgentThereIsNoSideEffectsSectionAndAPreviousRunWithoutThemSaysSo() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        history.record(RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 1));
        RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);
        service.setSideEffectsView(() -> new RunComparisonService.SideEffectsView(
                new AgentEvidence.Read("side-effects", true, true, null), null, () -> run()));

        assertThat(service.compare(null).sideEffects()).isNull();

        service.setCodeChanges(() -> true, limit -> null, wanted -> MethodRoutes.unavailable("x"));
        assertThat(service.compare(null).sideEffects()).satisfies(sideEffects -> {
            assertThat(sideEffects.available()).isFalse();
            assertThat(sideEffects.unavailableReason()).startsWith("The previous run kept no side effects");
        });

        service.setSideEffectsView(() -> new RunComparisonService.SideEffectsView(
                new AgentEvidence.Read("side-effects", false, true, "The Side Effects panel is disabled."),
                null,
                () -> run()));
        assertThat(service.compare(null).sideEffects().unavailableReason())
                .isEqualTo("The Side Effects panel is disabled.");
    }

    @Test
    void closingTheJournalKeepsTheRunsSideEffectsInItsSummaryAndAFailingReadKeepsNone() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        RunSideEffects sideEffects = run(key("processes", "process", "git", "GET /orders"));
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.recordRunIn(history, RunIdentity.start());
        aggregates.setRunSideEffects(() -> sideEffects);
        aggregates.onClose();
        assertThat(history.summaries().get(0).sideEffects()).isEqualTo(sideEffects);

        JournalAggregates failing = new JournalAggregates();
        failing.recordRunIn(history, RunIdentity.start());
        failing.setRunSideEffects(() -> {
            throw new IllegalStateException("broken");
        });
        failing.onClose();
        assertThat(history.summaries()).hasSize(2);
        assertThat(history.summaries().get(0).sideEffects()).isNull();
    }

    private static RunSideEffects run(RunSideEffects.Key... keys) {
        return new RunSideEffects(
                null,
                false,
                List.of(
                        new RunSideEffects.Sensor("network", null, null, 0),
                        new RunSideEffects.Sensor("files", null, null, 0),
                        new RunSideEffects.Sensor("processes", null, null, 0),
                        new RunSideEffects.Sensor("environment", null, null, 0)),
                List.of(keys));
    }

    private static RunSideEffects.Key key(String sensor, String kind, String target, String route) {
        return new RunSideEffects.Key(sensor, kind, target, "route", route, null, 1);
    }

    private void publish(JournalAggregates aggregates, RuntimeEvent event) {
        aggregates.onEntries(List.of(new JournalEntry(++sequence, event, event.estimatedBytes())));
    }

    private static RuntimeEvent http(String requestId, String route) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                2_000_000,
                CorrelationContext.forRequest(requestId),
                "http-nio-8080-exec-1",
                null,
                false,
                new HttpPayload(
                        "GET",
                        route,
                        route,
                        null,
                        200,
                        new ResourceUsage(1_000_000, 4_096, 1, 0, null, 1, List.of(), false)));
    }
}
