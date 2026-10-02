package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** M3-9: AI calls the framework reports are stamped with their request and remembered to skip their GenAI span. */
class AiCallEventsTests {

    private final List<RuntimeEvent> published = new ArrayList<>();
    private final RuntimeEventSink sink = published::add;

    @AfterEach
    void forget() {
        AiCallEvents.forget();
    }

    @Test
    void aCallIsStampedWithItsRequestAtCaptureWithoutTracing() {
        boolean accepted = AiCallEvents.publish(
                sink,
                CorrelationContext.forRequest("r1"),
                null,
                null,
                1_000,
                40_000_000,
                "http-1",
                new AiPayload("chat", "ollama", "llama3", 12L, 30L, "stop", false));

        assertThat(accepted).isTrue();
        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.AI);
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.traceId()).isNull();
            assertThat(event.durationNanos()).isEqualTo(40_000_000);
        });
        assertThat(AiCallEvents.recordedNatively(null, null, "chat")).isFalse();
    }

    @Test
    void theGenAiSpanOfACallReportedNativelyIsRecognizedByItsSpanOrByItsTraceAndOperation() {
        AiCallEvents.publish(
                sink,
                CorrelationContext.forExecution("x1"),
                "trace-1",
                "span-1",
                1_000,
                1,
                null,
                new AiPayload("chat", null, "gpt-4o", null, null, null, false));
        AiCallEvents.publish(
                sink,
                CorrelationContext.forRequest("r2").withTrace("trace-2", null),
                null,
                null,
                1_000,
                1,
                null,
                new AiPayload("embeddings", null, null, null, null, null, false));

        assertThat(((AiPayload) published.get(0).payload()).spanId()).isEqualTo("span-1");
        assertThat(published.get(1).traceId()).isEqualTo("trace-2");
        assertThat(AiCallEvents.recordedNatively("trace-1", "span-1", "chat")).isTrue();
        assertThat(AiCallEvents.recordedNatively("trace-1", "span-9", "chat"))
                .as("another call of the same trace")
                .isFalse();
        assertThat(AiCallEvents.recordedNatively("trace-2", "span-7", "embeddings"))
                .isTrue();
        assertThat(AiCallEvents.recordedNatively("trace-2", "span-8", "chat"))
                .as("only the operation reported natively is skipped")
                .isFalse();
    }

    @Test
    void aFrameworksNamesTokensAndFinishReasonsAreNormalized() {
        assertThat(AiCallEvents.operation("text_completion")).isEqualTo("chat");
        assertThat(AiCallEvents.operation("embedding")).isEqualTo("embeddings");
        assertThat(AiCallEvents.operation("image")).isNull();
        assertThat(AiCallEvents.finishReason("[\"LENGTH\", \"STOP\"]")).isEqualTo("length");
        assertThat(AiCallEvents.finishReason("[]")).isNull();
        assertThat(AiCallEvents.tokens("42")).isEqualTo(42L);
        assertThat(AiCallEvents.tokens("none")).isNull();
    }
}
