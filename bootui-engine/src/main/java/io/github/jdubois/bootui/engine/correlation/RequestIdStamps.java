package io.github.jdubois.bootui.engine.correlation;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers the request id current when a framework-owned object was recorded, for objects BootUI cannot extend, such
 * as Actuator's {@code HttpExchange} and {@code AuditEvent} ({@code docs/PLAN-v2.md} §5.1). The objects are held
 * weakly, so a stamp lives exactly as long as the repository that retains its object.
 *
 * <p>Keys must use identity equality, as both Actuator types do, so two equal-looking objects never share a stamp.
 * An object recorded when no request owns the work is not stamped.</p>
 *
 * @param <K> the stamped object type
 */
public final class RequestIdStamps<K> {

    private final Map<K, String> stamps = Collections.synchronizedMap(new WeakHashMap<>());
    private final CorrelationSource source;

    public RequestIdStamps() {
        this(new CorrelationSource());
    }

    public RequestIdStamps(CorrelationSource source) {
        this.source = source;
    }

    /** Stamps {@code key} with the request id current on this thread, if any. */
    public void stamp(K key) {
        if (key == null) {
            return;
        }
        String requestId = source.requestId();
        if (requestId != null) {
            stamps.put(key, requestId);
        }
    }

    /** The request id {@code key} was stamped with, or {@code null}. */
    public String requestId(K key) {
        return key == null ? null : stamps.get(key);
    }
}
