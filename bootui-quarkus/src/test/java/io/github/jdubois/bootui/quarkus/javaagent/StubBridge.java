package io.github.jdubois.bootui.quarkus.javaagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** A stub of the agent bridge's protocol 1, recording every call, for binding with {@code AgentBridgeAccess.bind}. */
public final class StubBridge {

    public static final int PROTOCOL = 1;

    static final List<String> CALLS = new ArrayList<>();

    private StubBridge() {}

    public static boolean attached() {
        return true;
    }

    public static Map<String, Object> status() {
        return new LinkedHashMap<>(Map.of("protocol", PROTOCOL, "attached", true));
    }

    public static Map<String, Object> claim(
            Map<String, ?> request, Supplier<Object> capture, Function<Object, AutoCloseable> reopen) {
        CALLS.add("claim " + request.get("mode") + ":" + request.get("application"));
        Map<String, Object> map = new LinkedHashMap<>(Map.of("status", "armed", "token", 3L, "generation", 1L));
        return map;
    }

    public static Map<String, Object> refine(long token, Map<String, ?> request) {
        CALLS.add("refine");
        return new LinkedHashMap<>(Map.of("status", "armed"));
    }

    public static Map<String, Object> disarm(long token) {
        CALLS.add("disarm");
        return new LinkedHashMap<>(Map.of("status", "disarmed"));
    }

    public static Map<String, Object> release(String application, String mode) {
        CALLS.add("release");
        return new LinkedHashMap<>(Map.of("status", "released"));
    }
}
