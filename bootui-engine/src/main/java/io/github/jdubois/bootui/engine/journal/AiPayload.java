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
 * @param spanId the span id of the call, which tells a GenAI span from the framework's own event for the same call, or
 *     {@code null} when unknown
 * @param completedNanos the {@link System#nanoTime()} when it completed, or {@code -1} when unknown, as for a GenAI span
 *     received over OTLP; it places the call on its request's clock ({@code docs/PLAN-v2.md} §5.5)
 * @param codePathStamp the BootUI agent's code-paths stamp of the instrumented method open on the issuing thread when the
 *     call started ({@code docs/PLAN-v2.md} §5.14, M5-4c), which names the request-tree node that issued it, or {@code 0}
 *     when unknown: without the agent, for a span received over OTLP, or when the call
 *     ended on another thread than the one that started it, as a streaming call
 */
public record AiPayload(
        String operation,
        String provider,
        String model,
        Long inputTokens,
        Long outputTokens,
        String finishReason,
        boolean failed,
        String spanId,
        long completedNanos,
        long codePathStamp)
        implements RuntimeEventPayload {

    /** A call without a code-paths stamp. */
    public AiPayload(
            String operation,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens,
            String finishReason,
            boolean failed,
            String spanId,
            long completedNanos) {
        this(operation, provider, model, inputTokens, outputTokens, finishReason, failed, spanId, completedNanos, 0L);
    }

    /** A call without its monotonic completion. */
    public AiPayload(
            String operation,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens,
            String finishReason,
            boolean failed,
            String spanId) {
        this(operation, provider, model, inputTokens, outputTokens, finishReason, failed, spanId, -1);
    }

    /** A call whose span id is unknown. */
    public AiPayload(
            String operation,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens,
            String finishReason,
            boolean failed) {
        this(operation, provider, model, inputTokens, outputTokens, finishReason, failed, null);
    }

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
                dictionary.shared(operation),
                dictionary.shared(provider),
                dictionary.shared(model),
                inputTokens,
                outputTokens,
                dictionary.shared(finishReason),
                failed,
                spanId,
                completedNanos,
                codePathStamp);
    }

    /** This call with the code-paths stamp {@code stamp}. */
    public AiPayload withCodePathStamp(long stamp) {
        return stamp == codePathStamp
                ? this
                : new AiPayload(
                        operation,
                        provider,
                        model,
                        inputTokens,
                        outputTokens,
                        finishReason,
                        failed,
                        spanId,
                        completedNanos,
                        stamp);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 64
                + JournalDictionary.retained(dictionary, operation)
                + JournalDictionary.retained(dictionary, provider)
                + JournalDictionary.retained(dictionary, model)
                + JournalDictionary.retained(dictionary, finishReason)
                + RuntimeEvent.stringBytes(spanId);
    }
}
