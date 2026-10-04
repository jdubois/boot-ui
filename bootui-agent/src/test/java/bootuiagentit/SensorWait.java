package bootuiagentit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Waits, after a claim, until the agent's sensors settled: their asynchronous install and self-test ran and every effect
 * of its verdict is applied. A hook's result is published before the verdict, so reading earlier is a race.
 *
 * <p>This is not a general lifecycle-completion predicate. A claim schedules work without changing the executors or
 * threads state synchronously, so any terminal state retained from an earlier generation ("installed", "failed",
 * "self-test-failed") satisfies it at once, and a sensor left "release-failed" never does. Use it after a first claim
 * of those sensors, or after a reclaim only of the inventory and code-paths sensors, whose idle flag covers the new job.
 */
final class SensorWait {

    static final long DEADLINE_SECONDS = 30;

    private SensorWait() {}

    /**
     * The status rows of the sensors {@code ids}, in that order, once each is settled; throws when one is not within
     * {@link #DEADLINE_SECONDS}, so the child exits with the status rather than letting a test read a sensor mid-test.
     */
    static List<Map<String, Object>> awaitSettled(Collection<String> ids) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        while (true) {
            Map<String, Object> status = status();
            List<Map<String, Object>> rows = rows(status, ids);
            if (rows != null) {
                return rows;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException(
                        "sensors " + ids + " never settled within " + DEADLINE_SECONDS + " s: " + status);
            }
            Thread.sleep(25);
        }
    }

    static Map<String, Object> awaitSettled(String id) throws Exception {
        return awaitSettled(List.of(id)).get(0);
    }

    /**
     * Whether a sensor's last install and self-test finished. The executors and threads sensors publish their state
     * last, after the verdict's effects. The inventory and code-paths sensors have no testing state and apply a verdict
     * after publishing it, so they are settled only once their worker is idle with a verdict.
     */
    static boolean settled(Map<String, Object> sensor) {
        String state = String.valueOf(sensor.get("state"));
        if (sensor.containsKey("idle")) {
            return Boolean.TRUE.equals(sensor.get("idle"))
                    && (Boolean.TRUE.equals(sensor.get("selfTestPassed"))
                            || sensor.get("selfTestError") != null
                            || state.contains("failed"));
        }
        return "installed".equals(state) || "failed".equals(state) || state.startsWith("self-test-failed");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> status, Collection<String> ids) {
        Map<String, Object> agent = (Map<String, Object>) status.get("agent");
        List<Object> sensors = agent == null ? List.of() : (List<Object>) agent.get("sensors");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> row = null;
            for (Object sensor : sensors) {
                if (id.equals(((Map<String, Object>) sensor).get("id"))) {
                    row = (Map<String, Object>) sensor;
                }
            }
            if (row == null || !settled(row)) {
                return null;
            }
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> status() throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        return (Map<String, Object>) bridge.getMethod("status").invoke(null);
    }
}
