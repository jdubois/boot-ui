package io.github.jdubois.bootui.autoconfigure.javaagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** A stub of the agent bridge's protocol 1, recording every call, for binding with {@code AgentBridgeAccess.bind}. */
public final class FakeBridge {

    public static final int PROTOCOL = 1;

    static final List<String> CALLS = new ArrayList<>();
    static final List<Map<String, Object>> REQUESTS = new ArrayList<>();

    private FakeBridge() {}

    static void reset() {
        CALLS.clear();
        REQUESTS.clear();
    }

    public static boolean attached() {
        return true;
    }

    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("protocol", PROTOCOL);
        map.put("attached", true);
        return map;
    }

    public static Map<String, Object> claim(
            Map<String, ?> request, Supplier<Object> capture, Function<Object, AutoCloseable> reopen) {
        CALLS.add("claim");
        REQUESTS.add(new LinkedHashMap<>(request));
        Map<String, Object> map = answer("armed");
        map.put("token", 7L);
        map.put("generation", 1L);
        return map;
    }

    public static Map<String, Object> refine(long token, Map<String, ?> request) {
        CALLS.add("refine");
        REQUESTS.add(new LinkedHashMap<>(request));
        return answer("armed");
    }

    public static Map<String, Object> disarm(long token) {
        CALLS.add("disarm");
        return answer("disarmed");
    }

    public static Map<String, Object> release(String application, String mode) {
        CALLS.add("release " + mode + ":" + application);
        return answer("released");
    }

    private static Map<String, Object> answer(String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        return map;
    }
}
