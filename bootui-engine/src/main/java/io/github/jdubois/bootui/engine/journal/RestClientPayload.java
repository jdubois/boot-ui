package io.github.jdubois.bootui.engine.journal;

/**
 * An outgoing REST client call's payload: its method, the authority it called (with the port the URI states), its path
 * as REST Client retained it, its status, which client made it, and whether it failed. Never its headers or body.
 */
public record RestClientPayload(
        String method, String authority, String path, Integer status, String clientType, boolean failed)
        implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 24
                + RuntimeEvent.stringBytes(method)
                + RuntimeEvent.stringBytes(authority)
                + RuntimeEvent.stringBytes(path)
                + RuntimeEvent.stringBytes(clientType);
    }
}
