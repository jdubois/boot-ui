package io.github.jdubois.bootui.engine.journal;

/**
 * An outgoing REST client call's payload: its method, the authority it called (with the port the URI states), its path
 * as REST Client retained it, its status, which client made it, whether it failed, and up to four application frames
 * above it. Never its headers or body.
 */
public record RestClientPayload(
        String method,
        String authority,
        String path,
        Integer status,
        String clientType,
        boolean failed,
        ApplicationFrames frames)
        implements RuntimeEventPayload {

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
                        method, authority, path, status, clientType, failed, frames.interned(dictionary));
    }

    @Override
    public int estimatedBytes() {
        return 24
                + RuntimeEvent.stringBytes(method)
                + RuntimeEvent.stringBytes(authority)
                + RuntimeEvent.stringBytes(path)
                + RuntimeEvent.stringBytes(clientType)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
