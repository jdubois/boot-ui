package io.github.jdubois.bootui.autoconfigure.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, in-memory record of the trace and HTTP-request context observed when each Spring Boot
 * audit (security) event was published.
 *
 * <p>Spring Security publishes its audit events ({@code AUTHENTICATION_FAILURE},
 * {@code AUTHORIZATION_DENIED}, …) synchronously on the request thread. {@link
 * SecurityAuditTraceListener} captures the active {@code traceId} (from {@link TraceContext}) and the
 * current request method/path at that moment and stores them here, keyed by the audit event's
 * identity. The Diagnostics dashboard later joins each {@code SecurityLogEventDto} back to this
 * context so a security event can be correlated with the HTTP request — and trace — that caused it,
 * instead of relying on time heuristics alone.
 *
 * <p>The store is dependency-free and thread-safe. It keeps the most recent {@code capacity} entries
 * and evicts the oldest, so an idle or long-running application cannot grow it without bound.
 */
public final class SecurityAuditTraceStore {

    /** Default number of audit-event contexts retained. */
    public static final int DEFAULT_CAPACITY = 1_000;

    /** Trace and request context captured when an audit event was published. */
    public record Captured(String traceId, String requestMethod, String requestPath) {

        boolean isEmpty() {
            return traceId == null && requestMethod == null && requestPath == null;
        }
    }

    private final int capacity;
    private final Map<String, Captured> entries;

    public SecurityAuditTraceStore() {
        this(DEFAULT_CAPACITY);
    }

    public SecurityAuditTraceStore(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Captured> eldest) {
                return size() > SecurityAuditTraceStore.this.capacity;
            }
        };
    }

    /**
     * Records the captured context for an audit event. No-ops when there is nothing useful to
     * correlate with (no trace id and no request context).
     */
    public synchronized void record(String timestamp, String type, String principal, Captured captured) {
        if (captured == null || captured.isEmpty()) {
            return;
        }
        entries.put(key(timestamp, type, principal), captured);
    }

    /** Returns the context captured for the matching audit event, or {@code null} when unknown. */
    public synchronized Captured lookup(String timestamp, String type, String principal) {
        return entries.get(key(timestamp, type, principal));
    }

    synchronized int size() {
        return entries.size();
    }

    private static String key(String timestamp, String type, String principal) {
        return value(timestamp) + '\u0000' + value(type) + '\u0000' + value(principal);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
