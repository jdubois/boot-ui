package io.github.jdubois.bootui.engine.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The legacy (MCP 2025-06-18) {@code tools/call} requests in flight, by request id, so a {@code
 * notifications/cancelled} can stop one.
 *
 * <p>MCP 2025-06-18 has no sessions at BootUI's endpoint, so ids are not scoped to a client: any local caller that
 * passes the endpoint's loopback, Host, cross-site, and token checks can cancel a call by its id. Two callers can use the
 * same id at the same time; such an id is ambiguous and a cancellation naming it is ignored rather than guessed. Clients
 * number their ids per connection (0, 1, 2…), so a late cancellation from one client can also name, and cancel, another
 * client's call that happens to use the same id while the first client's call has finished; BootUI accepts that
 * limitation rather than add a session header that would change MCP 2025-06-18's bytes.
 *
 * <p>The registry is bounded by the concurrency cap: a call registers after taking its permit and is removed before
 * that permit is released, whether it streams or blocks. A blocking call is also removed once it is answered (the
 * timeout or a cancellation), even while its abandoned tool still holds the permit, so entries never outnumber the
 * permits in use.
 */
final class McpInFlightCalls {

    private final Map<String, List<Registration>> calls = new HashMap<>();

    /** Registers {@code cancel} under {@code key}; close the returned registration when the call ends. */
    synchronized Registration register(String key, Runnable cancel) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cancel, "cancel");
        Registration registration = new Registration(key, cancel);
        calls.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(registration);
        return registration;
    }

    /**
     * Cancels the one call registered under {@code key}. Returns {@code false}, and does nothing, for an unknown or
     * finished id, or an id two calls share.
     */
    boolean cancel(String key) {
        Registration target;
        synchronized (this) {
            List<Registration> registered = key == null ? null : calls.get(key);
            if (registered == null || registered.size() != 1) {
                return false;
            }
            target = registered.get(0);
        }
        target.cancel.run();
        return true;
    }

    synchronized int size() {
        return calls.values().stream().mapToInt(List::size).sum();
    }

    private synchronized void remove(Registration registration) {
        List<Registration> registered = calls.get(registration.key);
        if (registered != null && registered.remove(registration) && registered.isEmpty()) {
            calls.remove(registration.key);
        }
    }

    /** One registered call. */
    final class Registration implements AutoCloseable {
        private final String key;
        private final Runnable cancel;

        private Registration(String key, Runnable cancel) {
            this.key = key;
            this.cancel = cancel;
        }

        @Override
        public void close() {
            remove(this);
        }
    }
}
