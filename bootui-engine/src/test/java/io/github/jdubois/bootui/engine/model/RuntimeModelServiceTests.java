package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
