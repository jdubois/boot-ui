package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunStartTests {

    @Test
    void aDataSourceUrlKeepsItsVendorAndModeOrHostOnly() {
        assertThat(ComparabilityFacts.urlShape("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"))
                .isEqualTo("jdbc:h2:mem");
        assertThat(ComparabilityFacts.urlShape("jdbc:h2:./data/shop")).isEqualTo("jdbc:h2:file");
        assertThat(ComparabilityFacts.urlShape("jdbc:h2:tcp://db.internal:9092/~/shop"))
                .isEqualTo("jdbc:h2://db.internal");
        assertThat(ComparabilityFacts.urlShape("jdbc:derby:memory:shop;create=true"))
                .isEqualTo("jdbc:derby:mem");
        assertThat(ComparabilityFacts.urlShape("jdbc:postgresql://localhost:5432/shop?password=secret"))
                .isEqualTo("jdbc:postgresql://localhost");
        assertThat(ComparabilityFacts.urlShape("jdbc:mysql://admin:secret@DB.example.com/shop"))
                .isEqualTo("jdbc:mysql://db.example.com");
        assertThat(ComparabilityFacts.urlShape("jdbc:sqlserver://sql1:1433;databaseName=shop;password=x"))
                .isEqualTo("jdbc:sqlserver://sql1");
        assertThat(ComparabilityFacts.urlShape("jdbc:oracle:thin:@//ora.internal:1521/SHOP"))
                .isEqualTo("jdbc:oracle://ora.internal");
        assertThat(ComparabilityFacts.urlShape("jdbc:tc:postgresql:16:///shop")).isEqualTo("jdbc:tc:postgresql");
        assertThat(ComparabilityFacts.urlShape("jdbc:postgresql://[::1]:5432/shop"))
                .isEqualTo("jdbc:postgresql://[::1]");
        assertThat(ComparabilityFacts.urlShape(null)).isEqualTo("unknown");
        assertThat(ComparabilityFacts.urlShape("not a url")).isEqualTo("unknown");
    }

    @Test
    void runsOnAnotherDatabaseProfileOrCacheAreNotComparableWithTheDatabaseFirst() {
        ComparabilityFacts h2 = facts(Map.of("dataSource", "jdbc:h2:mem:shop"), List.of("dev"), "none", true);
        ComparabilityFacts postgres = facts(
                Map.of("dataSource", "jdbc:postgresql://localhost/shop"),
                List.of("docker"),
                "CaffeineCacheManager",
                false);

        assertThat(h2.notComparableReasons(h2)).isEmpty();
        assertThat(postgres.notComparableReasons(h2))
                .containsExactly(
                        "The data sources differ: dataSource jdbc:h2:mem before, dataSource jdbc:postgresql://localhost"
                                + " now.",
                        "The active profiles differ: dev before, docker now.",
                        "The cache differs: none before, CaffeineCacheManager now.");
        assertThat(postgres.limitations(h2))
                .containsExactly("Tracing was on before and is off now, so links by trace id differ.");
    }

    @Test
    void theRunStartIsPublishedOnceWithItsSlowestStepsAndKeptInTheRunSummary() {
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = journal(run, JournalSource.all());
        JournalAggregates aggregates = new JournalAggregates();
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        aggregates.recordRunIn(history, run);
        journal.addListener(aggregates);
        List<StartupStepTiming> steps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            steps.add(new StartupStepTiming("spring.beans.instantiate", "bean" + i, i * 1_000_000L));
        }

        boolean published = RunStartEvents.publish(
                journal,
                10_000,
                3_000_000_000L,
                steps,
                List.of("dev"),
                Map.of("dataSource", "jdbc:postgresql://admin:secret@localhost:5432/shop"),
                null,
                true);
        journal.close();

        assertThat(published).isTrue();
        RunSummary.Header header = history.headers().get(0);
        RunStart start = header.runStart();
        assertThat(start.readyNanos()).isEqualTo(3_000_000_000L);
        assertThat(start.slowestSteps())
                .hasSize(RunStart.MAX_STEPS)
                .extracting(StartupStepTiming::bean)
                .startsWith("bean12", "bean11")
                .doesNotContain("bean2", "bean1");
        assertThat(start.facts().dataSources()).containsExactly(Map.entry("dataSource", "jdbc:postgresql://localhost"));
        assertThat(start.facts().cacheType()).isEqualTo("none");
        assertThat(start.facts().tracing()).isTrue();
        assertThat(start.facts().journalSources()).contains("lifecycle", "http", "sql");
        assertThat(history.summaries().get(0).header().runStart()).isEqualTo(start);
        assertThat(header.events()).isEqualTo(1);
    }

    @Test
    void theRunStartEventSpansTheStartupOutsideAnyThreadAndClearRecordingKeepsIt() {
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = journal(run, JournalSource.all());
        JournalAggregates aggregates = new JournalAggregates();
        journal.addListener(aggregates);

        RunStartEvents.publish(journal, 10_000, 2_500_000_000L, List.of(), List.of(), Map.of(), null, false);
        RunStartEvents.publish(journal, 20_000, 1L, List.of(), List.of("second"), Map.of(), null, false);
        journal.close();

        RuntimeEvent event = journal.entries().stream()
                .min(java.util.Comparator.comparingLong(JournalEntry::sequence))
                .orElseThrow()
                .event();
        assertThat(event.source()).isEqualTo(JournalSource.LIFECYCLE);
        assertThat(event.epochMillis()).isEqualTo(7_500);
        assertThat(event.durationNanos()).isEqualTo(2_500_000_000L);
        assertThat(event.thread()).isNull();
        assertThat(event.requestId()).isNull();
        assertThat(aggregates.runStart().facts().activeProfiles())
                .as("the first run start is the run's")
                .isEmpty();
        assertThat(aggregates.snapshot().threadFamilies()).isEmpty();

        aggregates.clear();

        assertThat(aggregates.runStart()).isNotNull();
    }

    @Test
    void nothingIsPublishedWhenTheLifecycleSourceIsOff() {
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = journal(run, EnumSet.of(JournalSource.HTTP));

        assertThat(RunStartEvents.publish(journal, 1, null, null, null, null, null, false))
                .isFalse();
        assertThat(RunStartEvents.publish(null, 1, null, null, null, null, null, false))
                .isFalse();
        journal.close();
    }

    @Test
    void aSummaryWithoutARunStartStillRoundTrips() {
        RunSummary summary = RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 1);

        byte[] encoded = RunSummaryCodec.encode(summary, RunHistory.MAX_SUMMARY_BYTES);

        assertThat(RunSummaryCodec.header(encoded).runStart()).isNull();
        assertThat(RunSummaryCodec.decode(encoded).header().runStart()).isNull();
    }

    private static ComparabilityFacts facts(
            Map<String, String> urls, List<String> profiles, String cache, boolean tracing) {
        return ComparabilityFacts.of(profiles, urls, cache, tracing, JournalSource.all());
    }

    private static RuntimeJournal journal(RunIdentity run, java.util.Set<JournalSource> sources) {
        return new RuntimeJournal(new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, sources), run, false);
    }
}
