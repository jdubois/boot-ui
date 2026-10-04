package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeModelServiceTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void readsTheStructureOncePerRunAndReprojectsOnlyWhenTheJournalRecordsMore() throws Exception {
        List<String> reads = new ArrayList<>();
        RuntimeModelService service = new RuntimeModelService(journal, null, runId -> {
            reads.add(runId);
            return PocFixture.structure();
        });
        request("/owners/{id}");

        RuntimeModel first = service.model();
        assertThat(service.model()).as("cached until the journal records more").isSameAs(first);
        request("/owners/{id}");
        RuntimeModel second = service.model();

        assertThat(second).isNotSameAs(first);
        assertThat(reads).containsExactly(journal.run().id());
        assertThat(second.executions(second.node(NodeType.ROUTE, "GET /owners/{id}")
                        .orElseThrow()
                        .id()))
                .isEqualTo(2);
        assertThat(second.node(NodeType.REPOSITORY, PocFixture.REPOSITORY)).isPresent();
    }

    @Test
    void aDisabledJournalOrAFailingStructureNeverFailsTheModel() throws Exception {
        assertThat(new RuntimeModelService(null, null, null).model().limitations())
                .singleElement()
                .satisfies(limitation -> assertThat(limitation).contains("bootui.runtime-journal.enabled=true"));

        request("/owners/{id}");
        RuntimeModel model = new RuntimeModelService(journal, null, runId -> {
                    throw new IllegalStateException("no beans yet");
                })
                .model();
        assertThat(model.node(NodeType.ROUTE, "GET /owners/{id}")).isPresent();
    }

    @Test
    void visibleProjectionReusesItsCacheUntilTheJournalOrPanelPolicyChanges() throws Exception {
        RuntimeModelService service = new RuntimeModelService(journal, null, runId -> PocFixture.structure());
        request("/owners/{id}");
        Map<String, Boolean> noSql = Map.of(BootUiPanels.HTTP_EXCHANGES, true);
        RuntimeModel first = service.model(noSql);
        assertThat(service.model(Map.of(BootUiPanels.HTTP_EXCHANGES, true))).isSameAs(first);

        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                2,
                2,
                CorrelationContext.forRequest("r-sql"),
                "http-1",
                null,
                false,
                new SqlPayload("select * from owners", null, "db", false)));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                3,
                3,
                CorrelationContext.forRequest("r-sql"),
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/owners/1", "/owners/{id}", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        RuntimeModel next = service.model(noSql);
        assertThat(next).isNotSameAs(first);
        assertThat(service.model(noSql)).isSameAs(next);

        RuntimeModel withSql = service.model(Map.of(BootUiPanels.HTTP_EXCHANGES, true, BootUiPanels.SQL_TRACE, true));
        assertThat(withSql).isNotSameAs(next);
        assertThat(withSql.node(NodeType.TABLE, "owners")).isPresent();
        assertThat(service.model(noSql).node(NodeType.TABLE, "owners")).isEmpty();

        journal.clear();
        RuntimeModel cleared = service.model(noSql);
        assertThat(cleared).isNotSameAs(next);
        assertThat(service.model(noSql)).isSameAs(cleared);
        assertThat(service.model(Map.of(BootUiPanels.HTTP_EXCHANGES, true, BootUiPanels.SQL_TRACE, true))
                        .node(NodeType.TABLE, "owners"))
                .isEmpty();
    }

    @Test
    void aRefreshedStructureInvalidatesTheVisibleProjectionWithoutNewEvents() throws Exception {
        AtomicBoolean available = new AtomicBoolean();
        RuntimeModelService service = new RuntimeModelService(
                journal, null, runId -> available.get() ? PocFixture.structure() : StructureSnapshot.empty(runId));
        request("/owners/{id}");
        Map<String, Boolean> visible = Map.of(BootUiPanels.HTTP_EXCHANGES, true);
        RuntimeModel before = service.model(visible);

        available.set(true);
        service.model();
        RuntimeModel after = service.model(visible);
        assertThat(after).isNotSameAs(before);
        assertThat(after.node(NodeType.REPOSITORY, PocFixture.REPOSITORY)).isPresent();
    }

    private void request(String template) throws InterruptedException {
        String requestId = "r" + System.nanoTime();
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1,
                1,
                CorrelationContext.forRequest(requestId),
                "http-1",
                null,
                false,
                new HttpPayload("GET", template.replace("{id}", "1"), template, null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }
}
