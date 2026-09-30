package io.github.jdubois.bootui.engine.journal;

/**
 * An HTTP exchange's payload: its method, its route label (the matched template, the declared route, or the masked
 * path, never a raw path with values), and its status.
 */
public record HttpPayload(String method, String route, int status) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(method) + RuntimeEvent.stringBytes(route);
    }
}
