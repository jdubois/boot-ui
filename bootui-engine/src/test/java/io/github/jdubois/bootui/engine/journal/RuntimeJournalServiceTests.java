package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeJournalServiceTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 100, 1_000_000, 2, 10, 10, JournalSource.all()),
            RunIdentity.start(),
            false);

    private final JournalAggregates aggregates = new JournalAggregates();

    private final RuntimeJournalService service = new RuntimeJournalService(journal, aggregates);

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void reportsTheJournalsBoundsCountsAndDropsByPropertyName() {
        journal.addListener(aggregates);
        journal.offer(event(JournalSource.REST_CLIENT, 1_000));
        journal.offer(event(JournalSource.SQL, 2_000));
        journal.offer(event(JournalSource.SQL, 3_000));
        journal.dispatchPending();

        RuntimeJournalStatusDto status = service.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.runId()).isEqualTo(journal.run().id());
        assertThat(status.retainedEvents()).isEqualTo(2);
        assertThat(status.retainedBytes()).isPositive();
        assertThat(status.maxEvents()).isEqualTo(100);
        assertThat(status.maxBytes()).isEqualTo(1_000_000);
        assertThat(status.queueCapacity()).isEqualTo(2);
        assertThat(status.recorded()).containsExactly(entry("sql", 1L), entry("rest-client", 1L));
        assertThat(status.dropped()).containsExactly(entry("sql", 1L));
        assertThat(status.droppedEvents()).isEqualTo(1);
        assertThat(status.oldestRetainedAt()).isEqualTo(1_000L);
    }

    @Test
    void clearingNeedsConfirmationThenDropsTheEventsAndAggregatesButKeepsTheCounts() {
        journal.addListener(aggregates);
        journal.offer(event(JournalSource.SQL, 1_000));
        journal.dispatchPending();

        RuntimeJournalService.Response unconfirmed = service.clear(new RuntimeJournalClearRequest(null));
        RuntimeJournalService.Response confirmed = service.clear(new RuntimeJournalClearRequest(true));

        assertThat(unconfirmed.status()).isEqualTo(400);
        assertThat(unconfirmed.body().status()).isEqualTo("blocked");
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().clearedEvents()).isEqualTo(1);
        assertThat(confirmed.body().message()).isEqualTo("Cleared 1 recorded event and the aggregates of this run.");
        assertThat(journal.entries()).isEmpty();
        assertThat(aggregates.snapshot().statements()).isEmpty();
        assertThat(service.status().recorded()).containsEntry("sql", 1L);
    }

    @Test
    void aMissingOrDisabledJournalReportsItselfDisabledAndCannotBeCleared() {
        RuntimeJournal disabled = new RuntimeJournal(RuntimeJournalSettings.disabled(), RunIdentity.start(), false);
        RuntimeJournalService none = new RuntimeJournalService(null, null);

        assertThat(none.status().enabled()).isFalse();
        assertThat(none.clear(new RuntimeJournalClearRequest(true)).status()).isEqualTo(404);
        assertThat(new RuntimeJournalService(disabled, null)
                        .clear(new RuntimeJournalClearRequest(true))
                        .body()
                        .status())
                .isEqualTo("unavailable");
        assertThat(new RuntimeJournalService(disabled, null).status().enabled()).isFalse();
        disabled.close();
    }

    private static Map.Entry<String, Long> entry(String key, Long value) {
        return Map.entry(key, value);
    }

    private static RuntimeEvent event(JournalSource source, long epochMillis) {
        return RuntimeEvent.of(
                source,
                epochMillis,
                1_000,
                CorrelationContext.NONE,
                "worker-1",
                null,
                false,
                source == JournalSource.SQL ? new SqlPayload("select 1", null, null, false) : null);
    }
}
