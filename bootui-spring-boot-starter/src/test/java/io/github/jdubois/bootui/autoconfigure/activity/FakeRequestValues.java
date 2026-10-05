package io.github.jdubois.bootui.autoconfigure.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stub of the bridge's request value holder, recording every call, for binding with {@code RequestValuesBinding}. */
public final class FakeRequestValues {

    public static volatile boolean active = true;
    public static final List<String> CALLS = new ArrayList<>();
    public static final Map<String, String> PUSHED = new LinkedHashMap<>();
    public static volatile Map<?, ?> late;
    public static volatile String[] lateKeys;

    private FakeRequestValues() {}

    public static synchronized void reset() {
        active = true;
        CALLS.clear();
        PUSHED.clear();
        late = null;
        lateKeys = null;
    }

    public static boolean active() {
        return active;
    }

    public static synchronized int begin(
            String requestId, String[] names, String[] values, Map<?, ?> lateSource, String[] keys) {
        CALLS.add("begin " + requestId);
        for (int i = 0; i < values.length; i++) {
            PUSHED.put(names[i], values[i]);
        }
        late = lateSource;
        lateKeys = keys;
        return values.length;
    }

    public static synchronized void end(String requestId) {
        CALLS.add("end " + requestId);
    }

    public static int match(String text, int kind, int[] spans, String[] names) {
        return 0;
    }

    public static Map<String, Object> status() {
        return Map.of();
    }
}
