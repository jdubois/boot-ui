package io.github.jdubois.bootui.engine.journal;

/**
 * An outgoing REST client call's payload: its method, the authority it called (with the port the URI states), its path
 * as REST Client retained it, its status, which client made it, whether it failed, and up to four application frames
 * above it. Never its headers or body.
 *
 * @param completedNanos the {@link System#nanoTime()} when it completed, or {@code -1} when unknown, which places it in
 *     its request and against its thread's transactions ({@code docs/PLAN-v2.md} §5.5)
 */
public record RestClientPayload(
        String method,
        String authority,
        String path,
        Integer status,
        String clientType,
        boolean failed,
        ApplicationFrames frames,
        long completedNanos)
        implements RuntimeEventPayload {

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

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return frames == null
                ? this
                : new RestClientPayload(
                        method,
                        authority,
                        path,
                        status,
                        clientType,
                        failed,
                        frames.interned(dictionary),
                        completedNanos);
    }

    @Override
    public int estimatedBytes() {
        return 32
                + RuntimeEvent.stringBytes(method)
                + RuntimeEvent.stringBytes(authority)
                + RuntimeEvent.stringBytes(path)
                + RuntimeEvent.stringBytes(clientType)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
