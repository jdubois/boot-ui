package io.github.jdubois.bootui.quarkus.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuarkusAiCallListenerTest {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final QuarkusAiCallListener listener =
            new QuarkusAiCallListener(journal, () -> CorrelationContext.forRequest("r1"));

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void recordsEachChatCallUnderTheRequestThatMadeItWithItsMetadataButNoMessage() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("What is the capital of France?"))
                .modelName("llama3")
                .build();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OLLAMA, attributes));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("Paris."))
                        .modelName("llama3:8b")
                        .tokenUsage(new TokenUsage(14, 3))
                        .finishReason(FinishReason.LENGTH)
                        .build(),
                request,
                ModelProvider.OLLAMA,
                attributes));
        Map<Object, Object> failing = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, failing));
        listener.onError(new ChatModelErrorContext(
                new IllegalStateException("refused"), request, ModelProvider.OPEN_AI, failing));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        List<RuntimeEvent> events =
                journal.entries().stream().map(entry -> entry.event()).toList();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.AI);
            assertThat(event.requestId()).isEqualTo("r1");
        });
        assertThat(events)
                .extracting(event -> (AiPayload) event.payload())
                .allSatisfy(payload -> assertThat(payload.completedNanos()).isNotEqualTo(-1))
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("completedNanos")
                .containsExactlyInAnyOrder(
                        new AiPayload("chat", "ollama", "llama3:8b", 14L, 3L, "length", false),
                        new AiPayload("chat", "openai", "llama3", null, null, null, true));
        assertThat(events.toString()).doesNotContain("France").doesNotContain("Paris");
    }
}
