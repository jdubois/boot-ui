package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** M3-9: Spring AI's model calls are recorded from its observation, stamped with their request, without tracing. */
class AiObservationJournalHandlerTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void recordsAChatCallFromItsKeyValuesUnderTheRequestThatMadeIt() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new AiObservationJournalHandler(() -> journal));

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            Observation chat = Observation.start("gen_ai.client.operation", registry);
            chat.lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                    .lowCardinalityKeyValue("gen_ai.system", "ollama")
                    .lowCardinalityKeyValue("gen_ai.request.model", "llama3")
                    .highCardinalityKeyValue("gen_ai.usage.input_tokens", "14")
                    .highCardinalityKeyValue("gen_ai.usage.output_tokens", "3")
                    .highCardinalityKeyValue("gen_ai.response.finish_reasons", "[\"STOP\"]");
            chat.stop();
            Observation other = Observation.start("spring.ai.chat.client", registry);
            other.stop();
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        assertThat(journal.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.event().source()).isEqualTo(JournalSource.AI);
            assertThat(entry.event().requestId()).isEqualTo("r1");
            AiPayload payload = (AiPayload) entry.event().payload();
            assertThat(payload)
                    .usingRecursiveComparison()
                    .ignoringFields("completedNanos")
                    .isEqualTo(new AiPayload("chat", "ollama", "llama3", 14L, 3L, "stop", false));
            assertThat(payload.completedNanos())
                    .as("its monotonic completion places it on its request's clock")
                    .isNotEqualTo(-1);
        });
    }
}
