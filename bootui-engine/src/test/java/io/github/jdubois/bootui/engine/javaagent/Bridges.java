package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Drives the real bridge class from the test class path: resets it and installs a stub agent. */
final class Bridges {

    private Bridges() {}

    static void reset() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static AgentBridgeAccess access() {
        return new AgentBridgeAccess(AgentBridge.class);
    }

    /** A stub agent recording every request; {@code status} answers what the real agent's handler answers. */
    static final class StubAgent implements Function<Map<String, Object>, Map<String, Object>> {

        final List<Map<String, Object>> requests = new ArrayList<>();
        String version = "1.19.0";
        Map<String, Object> installer;
        List<Object> sensors = new ArrayList<>();
        Map<String, Object> claimAnswer;

        @Override
        public synchronized Map<String, Object> apply(Map<String, Object> request) {
            String op = String.valueOf(request.get("op"));
            if ("status".equals(op)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("status", "ok");
                map.put("version", version);
                map.put("loadMode", "javaagent");
                map.put("jar", "/tmp/bootui-agent-" + version + ".jar");
                map.put("startupMicros", 1234L);
                map.put("sensors", sensors);
                map.put("installer", installer);
                return map;
            }
            requests.add(new LinkedHashMap<>(request));
            if ("claim".equals(op) && claimAnswer != null) {
                return claimAnswer;
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        }

        synchronized List<String> ops() {
            return requests.stream().map(r -> String.valueOf(r.get("op"))).toList();
        }

        static StubAgent install() {
            StubAgent agent = new StubAgent();
            if (!AgentBridge.install(agent)) {
                throw new IllegalStateException("an agent is already installed");
            }
            return agent;
        }
    }
}
