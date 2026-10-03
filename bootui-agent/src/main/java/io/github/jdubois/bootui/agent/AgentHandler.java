package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The agent's side of the bridge: the bridge calls it on every claim transition and for status. Calls are serialized
 * here. The bridge calls the agent after its compare-and-set, outside any lock, so calls can arrive out of order: every
 * transition carries a generation from one JVM-wide sequence, and a claim or release older than the last one applied is
 * ignored, as is a refine or disarm of another generation than the current claim's. A claim asking for the
 * {@code executors} sensor installs it once and self-tests it (PLAN-v2 M5-2), and a release removes it; a claim without
 * a sensor installs nothing, unless {@link AgentTestHook} enables the diagnostic probe.
 */
final class AgentHandler implements Function<Map<String, Object>, Map<String, Object>> {

    private final Instrumentation instrumentation;
    private final AgentTestHook hook;
    private final String version;
    private final String loadMode;
    private final String jarPath;
    private final long startupMicros;
    private AgentInstaller installer;
    private ExecutorSensor executors;
    private long generation;
    private boolean armed;
    private List<String> packages = Collections.emptyList();

    AgentHandler(
            Instrumentation instrumentation,
            AgentTestHook hook,
            String version,
            String loadMode,
            String jarPath,
            long startupMicros) {
        this.instrumentation = instrumentation;
        this.hook = hook;
        this.version = version;
        this.loadMode = loadMode;
        this.jarPath = jarPath;
        this.startupMicros = startupMicros;
    }

    @Override
    public synchronized Map<String, Object> apply(Map<String, Object> request) {
        String op = String.valueOf(request.get("op"));
        long requested = request.get("generation") instanceof Long ? (Long) request.get("generation") : -1L;
        switch (op) {
            case "claim":
                if (requested < generation) {
                    return answer("ignored", "an older claim generation");
                }
                generation = requested;
                armed = true;
                packages = strings(request.get("packages"));
                hook.onClaim(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(request)));
                List<String> probe = hook.probePackages();
                if (!probe.isEmpty()) {
                    installer().install(probe);
                }
                if (strings(request.get("sensors")).contains(TaskPropagation.SENSOR)) {
                    executors().claimed(generation);
                }
                return answer("ok", null);
            case "refine":
                if (requested == generation) {
                    packages = strings(request.get("packages"));
                }
                return answer("ok", null);
            case "disarm":
                if (requested == generation) {
                    armed = false;
                }
                return answer("ok", null);
            case "release":
                if (requested < generation) {
                    return answer("ignored", "an older release");
                }
                generation = requested;
                armed = false;
                if (installer != null) {
                    installer.release();
                }
                if (executors != null) {
                    executors.release();
                }
                return answer("ok", null);
            case "status":
                return status();
            default:
                return answer(AgentBridge.FAILED, "unknown operation " + op);
        }
    }

    private ExecutorSensor executors() {
        if (executors == null) {
            executors = new ExecutorSensor(instrumentation, hook.privilegedInstall());
        }
        return executors;
    }

    private AgentInstaller installer() {
        if (installer == null) {
            installer = new AgentInstaller(instrumentation, hook);
        }
        return installer;
    }

    private Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", "ok");
        map.put("version", version);
        map.put("loadMode", loadMode);
        map.put("jar", jarPath);
        map.put("startupMicros", Long.valueOf(startupMicros));
        map.put("generation", Long.valueOf(generation));
        map.put("armed", Boolean.valueOf(armed));
        map.put("packages", new ArrayList<String>(packages));
        List<Object> sensors = new ArrayList<Object>();
        if (executors != null) {
            sensors.add(executors.status());
        }
        map.put("sensors", sensors);
        map.put("installer", installer == null ? null : installer.status());
        return map;
    }

    private static Map<String, Object> answer(String status, String reason) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        return map;
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<String>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return Collections.unmodifiableList(list);
    }
}
