package io.github.jdubois.bootui.engine.journal;

/**
 * An outgoing REST client call's payload: its method, the authority it called (with the port the URI states), its path
 * as REST Client retained it, its status, which client made it, whether it failed, and up to four application frames
 * above it. Never its headers or body.
 *
 * @param completedNanos the {@link System#nanoTime()} when it completed, or {@code -1} when unknown, which places it in
 *     its request and against its thread's transactions ({@code docs/PLAN-v2.md} §5.5)
 * @param codePathStamp the BootUI agent's code-paths stamp of the instrumented method open on the issuing thread when the
 *     call was recorded ({@code docs/PLAN-v2.md} §5.14, M5-4c), which names the request-tree node that issued it, or {@code 0}
 *     when unknown: without the agent, or when the recorder ran on another thread
 */
public record RestClientPayload(
        String method,
        String authority,
        String path,
        Integer status,
        String clientType,
        boolean failed,
        ApplicationFrames frames,
        long completedNanos,
        long codePathStamp)
        implements RuntimeEventPayload {

    /** A call without a code-paths stamp. */
    public RestClientPayload(
            String method,
            String authority,
            String path,
            Integer status,
            String clientType,
            boolean failed,
            ApplicationFrames frames,
            long completedNanos) {
        this(method, authority, path, status, clientType, failed, frames, completedNanos, 0L);
    }

    /** A call without its monotonic completion. */
    public RestClientPayload(
            String method,
            String authority,
            String path,
            Integer status,
            String clientType,
            boolean failed,
            ApplicationFrames frames) {
        this(method, authority, path, status, clientType, failed, frames, -1);
    }

    /** A call without application frames. */
    public RestClientPayload(
            String method, String authority, String path, Integer status, String clientType, boolean failed) {
        this(method, authority, path, status, clientType, failed, null);
    }

    /**
     * This call with its method, authority, client type, and frames replaced by the run's shared copies; its path,
     * which may hold ids, stays its own.
     */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new RestClientPayload(
                dictionary.shared(method),
                dictionary.shared(authority),
                path,
                status,
                dictionary.shared(clientType),
                failed,
                frames == null ? null : frames.interned(dictionary),
                completedNanos,
                codePathStamp);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 40
                + JournalDictionary.retained(dictionary, method)
                + JournalDictionary.retained(dictionary, authority)
                + JournalDictionary.retained(dictionary, path)
                + JournalDictionary.retained(dictionary, clientType)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
