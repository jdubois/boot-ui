package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Filter;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** §5.18's control and availability markers (M4-7). */
class ControlMarkersTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aBootUiActionIsRecordedFromBootUisOwnRequestWithItsPathButNeverItsQueryOrBody() throws Exception {
        boolean recorded;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            assertThat(journal.offer(sql()))
                    .as("BootUI's own work stays out of the journal")
                    .isFalse();
            recorded = ControlMarkers.action(journal, "loggers", "post", "/loggers/com.example.orders?level=DEBUG");
        }

        assertThat(recorded).isTrue();
        LifecyclePayload marker = only();
        assertThat(marker.kind()).isEqualTo(LifecyclePayload.ACTION);
        assertThat(marker.target()).isEqualTo("loggers: POST /loggers/com.example.orders");
        assertThat(marker.marker()).isTrue();
        assertThat(ControlMarkers.targetName(marker)).isEqualTo("com.example.orders");
        assertThat(ControlMarkers.targetName(
                        new LifecyclePayload(LifecyclePayload.ACTION, "cache: POST /cache/clear", null)))
                .as("a verb names the action, not what it targeted")
                .isNull();
    }

    @Test
    void availabilityRefreshesAndShutdownAreMarkersAndAConfigurationRefreshNamesAtMostTenKeys() throws Exception {
        ControlMarkers.availability(journal, "Readiness REFUSING_TRAFFIC");
        ControlMarkers.configRefresh(
                journal,
                IntStream.rangeClosed(1, 12).mapToObj(i -> "app.key" + i).toList());
        ControlMarkers.shutdown(journal);
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        List<ActivityEntryDto> rows = new JournalActivityFeed(1_000, 3, RouteTemplateResolver::empty)
                .render(journal.entries(), entry -> "e" + entry.sequence(), "run", Filter.NONE, 0)
                .entries();

        assertThat(rows)
                .allMatch(row -> row.type().equals(JournalActivityFeed.TYPE_MARKER))
                .extracting(ActivityEntryDto::summary)
                .containsExactlyInAnyOrder(
                        "Availability changed", "Configuration refreshed", "Application shutting down");
        assertThat(rows)
                .filteredOn(row -> row.summary().equals("Configuration refreshed"))
                .singleElement()
                .extracting(ActivityEntryDto::detail)
                .asString()
                .startsWith("app.key1, app.key10, app.key11, app.key12, app.key2")
                .endsWith(" and 2 more");
    }

    @Test
    void withoutTheLifecycleSourceNoMarkerIsRecorded() {
        RuntimeJournal withoutLifecycle =
                new RuntimeJournal(RuntimeJournalSettings.of(true, 1_000, null, 100, "http,sql"), RunIdentity.start());
        try {
            assertThat(ControlMarkers.action(withoutLifecycle, "cache", "POST", "/cache/clear"))
                    .isFalse();
            assertThat(ControlMarkers.shutdown(null)).isFalse();
        } finally {
            withoutLifecycle.close();
        }
    }

    private LifecyclePayload only() throws InterruptedException {
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        return journal.entries().stream()
                .map(entry -> entry.event().payload())
                .filter(LifecyclePayload.class::isInstance)
                .map(LifecyclePayload.class::cast)
                .reduce((first, second) -> {
                    throw new AssertionError("More than one marker");
                })
                .orElseThrow();
    }

    private static RuntimeEvent sql() {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1,
                1,
                CorrelationContext.NONE,
                "bootui",
                null,
                false,
                new SqlPayload("select 1", null, "db", false));
    }
}
