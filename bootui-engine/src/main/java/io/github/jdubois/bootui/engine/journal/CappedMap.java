package io.github.jdubois.bootui.engine.journal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A map with a cardinality cap ({@code docs/PLAN-v2.md} §5.2): once it holds {@code cap} keys, a new key's values go
 * to one visible {@link #OTHER} entry, and the events routed there are counted, so a dynamic path or an unparameterized
 * statement can neither exhaust memory nor silently disappear. Not thread-safe; the aggregates guard it.
 *
 * @param <V> the aggregate held per key
 */
final class CappedMap<V> {

    /** The key of the overflow entry. */
    static final String OTHER = "Other";

    private final int cap;
    private final Supplier<V> factory;
    private final Map<String, V> values = new LinkedHashMap<>();
    private V other;
    private long overflowed;

    CappedMap(int cap, Supplier<V> factory) {
        this.cap = Math.max(1, cap);
        this.factory = factory;
    }

    /** The aggregate of {@code key}, created while under the cap, otherwise the {@link #OTHER} aggregate. */
    V get(String key) {
        V value = values.get(key);
        if (value != null) {
            return value;
        }
        if (values.size() < cap) {
            value = factory.get();
            values.put(key, value);
            return value;
        }
        overflowed++;
        if (other == null) {
            other = factory.get();
        }
        return other;
    }

    /** The aggregates by key, in first-seen order, with the {@link #OTHER} aggregate last when it exists. */
    Map<String, V> entries() {
        if (other == null) {
            return Collections.unmodifiableMap(values);
        }
        Map<String, V> all = new LinkedHashMap<>(values);
        all.put(OTHER, other);
        return Collections.unmodifiableMap(all);
    }

    /** The events routed to {@link #OTHER} because the cap was reached. */
    long overflowed() {
        return overflowed;
    }

    int cap() {
        return cap;
    }

    void clear() {
        values.clear();
        other = null;
        overflowed = 0;
    }
}
