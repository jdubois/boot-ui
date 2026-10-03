package bootuiagentit.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One simulated application run, defined by its own class loader as a DevTools restart would be: it claims the agent
 * the way the engine does (fresh capture and reopen objects from this run's classes), exercises a probed class, and
 * disarms at the end.
 */
public final class RunApp {

    /** One per run, numbered, so a heap walk can tell which runs are still reachable. */
    static final Sentinel SENTINEL = new Sentinel(Integer.getInteger("bootui.agent.it.run", -1));

    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;

    private RunApp() {}

    @SuppressWarnings("unchecked")
    public static long claim() throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "it");
        request.put("owner", "it run " + SENTINEL.run);
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit.run"));
        Object marker = new Object();
        capture = () -> marker == SENTINEL ? marker : null;
        reopen = snapshot -> marker == SENTINEL ? null : () -> {};
        Map<String, Object> result =
                (Map<String, Object>) bridge.getMethod("claim", Map.class, Supplier.class, Function.class)
                        .invoke(null, request, capture, reopen);
        if (!"armed".equals(result.get("status"))) {
            throw new IllegalStateException("claim not armed: " + result);
        }
        Probed.touch(SENTINEL.run);
        return (Long) result.get("token");
    }

    public static void disarm(long token) throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        bridge.getMethod("disarm", long.class).invoke(null, token);
        Probed.touch(SENTINEL.run);
        capture = null;
        reopen = null;
    }

    static final class Sentinel {
        final int run;

        Sentinel(int run) {
            this.run = run;
        }
    }
}
