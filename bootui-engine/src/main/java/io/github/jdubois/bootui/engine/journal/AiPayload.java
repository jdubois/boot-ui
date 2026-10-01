package io.github.jdubois.bootui.engine.journal;

/**
 * One AI operation's metadata, from a recognized GenAI span ({@code docs/PLAN-v2.md} §5.5): never its prompt,
 * completion, tool arguments, or results.
 *
 * @param operation {@code chat}, {@code embeddings}, {@code tool}, or {@code retrieval}
 * @param provider the provider, such as {@code openai}, or {@code null}
 * @param model the model that answered, or the one requested, or {@code null}
 * @param inputTokens the input tokens it reported, or {@code null} when it reported none
 * @param outputTokens the output tokens it reported, or {@code null} when it reported none
 * @param finishReason why the model stopped, such as {@code stop} or {@code length}, or {@code null}
 * @param failed whether the span ended in error
 */
public record AiPayload(
        String operation,
        String provider,
        String model,
        Long inputTokens,
        Long outputTokens,
        String finishReason,
        boolean failed)
        implements RuntimeEventPayload {

    public static final String CHAT = "chat";
    public static final String EMBEDDINGS = "embeddings";
    public static final String TOOL = "tool";
    public static final String RETRIEVAL = "retrieval";

    /** Whether the model stopped because it reached its length limit. */
    public boolean lengthLimited() {
        return finishReason != null
                && (finishReason.equalsIgnoreCase("length") || finishReason.equalsIgnoreCase("max_tokens"));
    }

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new AiPayload(
                operation,
                dictionary.shared(provider),
                dictionary.shared(model),
                inputTokens,
                outputTokens,
                dictionary.shared(finishReason),
                failed);
    }

    @Override
    public int estimatedBytes() {
        return 48
                + RuntimeEvent.stringBytes(provider)
                + RuntimeEvent.stringBytes(model)
                + RuntimeEvent.stringBytes(finishReason);
    }
}
