package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunBaselineFileTests {

    @TempDir
    Path directory;

    @Test
    void aSummaryWrittenAtTheEndOfARunIsReadBackWhole() throws IOException {
        RunSummary summary = summary();
        RunBaselineFile file = new RunBaselineFile(directory.resolve("bootui-baseline.bin"), "shop", "2.0.0");

        file.write(summary);
        file.write(summary);
        RunBaselineFile.Read read = file.read();

        assertThat(read.ignoredReason()).isNull();
        assertThat(read.summary().header().runId()).isEqualTo(summary.header().runId());
        assertThat(read.summary().aggregates().edges())
                .extracting(ObservedEdge::count)
                .containsExactly(2L);
        assertThat(read.summary().aggregates().run())
                .isEqualTo(summary.aggregates().run());
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files).as("no temporary file is left behind").containsExactly(file.path());
        }
        assertThat(Files.readString(file.path(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("the file holds no SQL text, only its literal-free fingerprint")
                .doesNotContain("where id = 42")
                .contains("orders");
    }

    @Test
    void aMissingFileIsNoPreviousRunAndAnUnsetPropertyNamesNoFile() {
        assertThat(new RunBaselineFile(directory.resolve("absent.bin"), "shop").read())
                .isEqualTo(new RunBaselineFile.Read(null, null));
        assertThat(RunBaselineFile.of(null, "shop")).isNull();
        assertThat(RunBaselineFile.of("  ", "shop")).isNull();
        Path configured =
                RunBaselineFile.of("target/bootui-baseline.bin", "shop").path();
        assertThat(configured.isAbsolute()).isTrue();
        assertThat(configured.endsWith(Path.of("target", "bootui-baseline.bin")))
                .isTrue();
    }

    @Test
    void aFileFromAnotherVersionOrApplicationOrNotASummaryIsIgnoredWithTheReason() throws IOException {
        Path path = directory.resolve("bootui-baseline.bin");
        new RunBaselineFile(path, "shop", "2.0.0").write(summary());

        assertThat(new RunBaselineFile(path, "shop", "2.1.0").read())
                .satisfies(read -> assertThat(read.summary()).isNull())
                .extracting(RunBaselineFile.Read::ignoredReason)
                .asString()
                .startsWith("The baseline file " + path.toAbsolutePath() + " was ignored")
                .contains("BootUI 2.0.0 wrote it, and this is BootUI 2.1.0");
        assertThat(new RunBaselineFile(path, "billing", "2.0.0").read().ignoredReason())
                .contains("written for the application 'shop', and this is 'billing'");

        Files.writeString(path, "not a baseline");
        assertThat(new RunBaselineFile(path, "shop", "2.0.0").read().ignoredReason())
                .contains("not a BootUI baseline file");

        Files.write(path, new byte[RunBaselineFile.MAX_FILE_BYTES + 1]);
        assertThat(new RunBaselineFile(path, "shop", "2.0.0").read().ignoredReason())
                .contains("larger than a run summary can be");
    }

    @Test
    void aMissingDirectoryIsNeverCreated() {
        Path path = directory.resolve("tagret").resolve("bootui-baseline.bin");
        RunBaselineFile file = new RunBaselineFile(path, "shop", "2.0.0");

        assertThatThrownBy(() -> file.write(summary())).isInstanceOf(NoSuchFileException.class);
        assertThat(path.getParent()).doesNotExist();
    }

    @Test
    void aBaselineExcludesTruncatedSqlLiteralValues() throws IOException {
        String secret = "sëcrét-baseline";
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(java.util.List.of(
                entry(1, sql("r1", "select * from orders where note = $body$" + secret)),
                entry(2, http("r1", "/api/orders", 200))));
        RunBaselineFile file = new RunBaselineFile(directory.resolve("bootui-baseline.bin"), "shop", "2.0.0");

        file.write(RunSummary.of(RunIdentity.start(), aggregates.snapshot(), 3_000));

        assertThat(new String(Files.readAllBytes(file.path()), StandardCharsets.UTF_8))
                .doesNotContain(secret);
        assertThat(file.read().summary().aggregates().statements())
                .extracting(JournalAggregates.StatementStats::fingerprint)
                .containsExactly("select * from orders where note = ?");
    }

    @Test
    void theAggregatesReadTheFileWhenTheHistoryKeepsNoRunAndWriteItWhenTheRunEnds() throws IOException {
        Path path = directory.resolve("bootui-baseline.bin");
        RunBaselineFile file = new RunBaselineFile(path, "shop", "2.0.0");
        RunSummary earlier = summary();
        file.write(earlier);

        RunHistory afterRestart = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = journal(run);
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.recordRunIn(afterRestart, run, file);
        journal.addListener(aggregates);

        assertThat(afterRestart.headers())
                .extracting(RunSummary.Header::runId)
                .containsExactly(earlier.header().runId());
        assertThat(afterRestart.baselineNote()).contains("read from the baseline file");

        journal.offer(http("r9", "/api/health", 200));
        journal.close();

        assertThat(file.read().summary().header().runId()).isEqualTo(run.id());
        assertThat(afterRestart.headers())
                .extracting(RunSummary.Header::runId)
                .containsExactly(run.id(), earlier.header().runId());

        RunHistory afterReload = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        afterReload.record(earlier);
        new JournalAggregates().recordRunIn(afterReload, RunIdentity.start(), file);
        assertThat(afterReload.headers())
                .as("a history that keeps a run does not read the file")
                .extracting(RunSummary.Header::runId)
                .containsExactly(earlier.header().runId());
        assertThat(afterReload.baselineNote()).isNull();
    }

    @Test
    void anUnusableFileLeavesTheHistoryEmptyWithTheReason() throws IOException {
        Path path = directory.resolve("bootui-baseline.bin");
        new RunBaselineFile(path, "shop", "1.19.0").write(summary());
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);

        history.loadBaseline(new RunBaselineFile(path, "shop", "2.0.0"));

        assertThat(history.headers()).isEmpty();
        assertThat(history.baselineNote()).contains("BootUI 1.19.0 wrote it");
    }

    private static RunSummary summary() {
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(java.util.List.of(
                entry(1, sql("r1", "select * from orders where id = 42")),
                entry(2, sql("r1", "select * from orders where id = 43")),
                entry(3, http("r1", "/api/orders/{id}", 200))));
        return RunSummary.of(RunIdentity.start(), aggregates.snapshot(), 10);
    }

    private static RuntimeJournal journal(RunIdentity run) {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()), run, false);
    }

    private static JournalEntry entry(long sequence, RuntimeEvent event) {
        return new JournalEntry(sequence, event, event.estimatedBytes());
    }

    private static RuntimeEvent sql(String requestId, String sql) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                1_000_000,
                CorrelationContext.forRequest(requestId),
                "http-1",
                null,
                false,
                new SqlPayload(sql, null, "db", false));
    }

    private static RuntimeEvent http(String requestId, String template, int status) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                2_000,
                2_000_000,
                CorrelationContext.forRequest(requestId),
                "http-1",
                null,
                false,
                new HttpPayload("GET", template, template, null, status));
    }
}
