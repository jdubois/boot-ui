package io.github.jdubois.bootui.quarkus.ai;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jdubois.bootui.engine.journal.AiCallEvents;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Map;

/**
 * Records each chat model call Quarkus LangChain4j makes in the runtime journal's {@code ai} source ({@code
 * docs/PLAN-v2.md} §5.18, M3-9), stamped with the request or execution that made it, so AI usage needs no tracing.
 * Quarkus LangChain4j registers every {@link ChatModelListener} bean with its models. It keeps the model, provider,
 * token counts, and finish reason, never the messages. The deployment processor registers it only when LangChain4j is
 * on the application's classpath.
 */
@ApplicationScoped
public class QuarkusAiCallListener implements ChatModelListener {

    private static final Object STARTED = new Object();

    private final RuntimeJournal journal;
    private final CorrelationContextProvider correlation;

    @Inject
    public QuarkusAiCallListener(RuntimeJournal journal, CorrelationContextProvider correlation) {
        this.journal = journal;
        this.correlation = correlation;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            context.attributes()
                    .put(
                            STARTED,
                            new Started(
                                    System.nanoTime(),
                                    System.currentTimeMillis(),
                                    correlation.current(),
                                    Thread.currentThread().getName()));
        } catch (RuntimeException | LinkageError ex) {
            // Recording never disturbs the call it observes.
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            ChatResponse response = context.chatResponse();
            ChatResponseMetadata metadata = response == null ? null : response.metadata();
            TokenUsage usage = metadata == null ? null : metadata.tokenUsage();
            publish(
                    context.attributes(),
                    provider(context.modelProvider()),
                    metadata != null && metadata.modelName() != null
                            ? metadata.modelName()
                            : requestModel(context.chatRequest()),
                    usage == null || usage.inputTokenCount() == null
                            ? null
                            : usage.inputTokenCount().longValue(),
                    usage == null || usage.outputTokenCount() == null
                            ? null
                            : usage.outputTokenCount().longValue(),
                    metadata == null || metadata.finishReason() == null
                            ? null
                            : metadata.finishReason().name().toLowerCase(Locale.ROOT),
                    false);
        } catch (RuntimeException | LinkageError ex) {
            // Recording never disturbs the call it observes.
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        try {
            publish(
                    context.attributes(),
                    provider(context.modelProvider()),
                    requestModel(context.chatRequest()),
                    null,
                    null,
                    null,
                    true);
        } catch (RuntimeException | LinkageError ex) {
            // Recording never disturbs the call it observes.
        }
    }

    private void publish(
            Map<Object, Object> attributes,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens,
            String finishReason,
            boolean failed) {
        if (!(attributes.get(STARTED) instanceof Started started)) {
            return;
        }
        long completed = System.nanoTime();
        AiCallEvents.publish(
                journal,
                started.correlation(),
                null,
                null,
                started.epochMillis(),
                completed - started.nanos(),
                completed,
                started.thread(),
                new AiPayload(AiPayload.CHAT, provider, model, inputTokens, outputTokens, finishReason, failed));
    }

    /** A provider's name as GenAI spans name it, such as {@code openai} for {@code OPEN_AI}. */
    static String provider(Object provider) {
        return provider == null ? null : provider.toString().replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String requestModel(ChatRequest request) {
        return request == null || request.parameters() == null
                ? null
                : request.parameters().modelName();
    }

    private record Started(long nanos, long epochMillis, CorrelationContext correlation, String thread) {}
}
