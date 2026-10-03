package io.github.jdubois.bootui.engine.javaagent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What this application asks the BootUI agent's sensors to do ({@code docs/PLAN-v2.md} M5-2): which sensors to install
 * ({@code bootui.agent.sensors}), and for the {@code executors} sensor, which tasks and worker threads to leave alone
 * because they already propagate their context ({@code bootui.agent.executors.skip-tasks},
 * {@code bootui.agent.executors.skip-threads}), and how long a handoff's work is attributed to its request
 * ({@code bootui.agent.executors.max-handoff}).
 *
 * @param sensors the sensors to install, such as {@code executors}
 * @param skipTasks task class-name prefixes the executors sensor never propagates
 * @param skipThreads worker thread-name prefixes the executors sensor never propagates to
 * @param maxHandoff how long a handoff's work is attributed to its request
 */
public record AgentSensorSettings(
        List<String> sensors, List<String> skipTasks, List<String> skipThreads, Duration maxHandoff) {

    /** The executors sensor. */
    public static final String EXECUTORS = "executors";

    /** The default {@code bootui.agent.sensors}. */
    public static final List<String> DEFAULT_SENSORS = List.of(EXECUTORS);

    /**
     * The default {@code bootui.agent.executors.skip-tasks}: BootUI's own and Micrometer's context-propagating wrappers,
     * Spring's context-propagating task decorator, and JDK, connection-pool, and cache internals (Caffeine runs its
     * maintenance on the common pool).
     */
    public static final List<String> DEFAULT_SKIP_TASKS = List.of(
            "io.github.jdubois.bootui.engine.correlation.ManagedTasks",
            "io.micrometer.context.",
            "org.springframework.core.task.support.ContextPropagatingTaskDecorator",
            "jdk.internal.",
            "sun.",
            "com.zaxxer.hikari.",
            "com.github.benmanes.caffeine.");

    /** The default {@code bootui.agent.executors.skip-threads}: Vert.x's own threads and BootUI's. */
    public static final List<String> DEFAULT_SKIP_THREADS = List.of("vert.x-", "bootui-");

    /**
     * Reactor's scheduler thread prefixes, which the WebFlux adapter adds when Reactor's automatic context propagation
     * already carries BootUI's context across them.
     */
    public static final List<String> REACTOR_SKIP_THREADS = List.of("parallel-", "boundedElastic-", "single-");

    public AgentSensorSettings {
        sensors = clean(sensors);
        skipTasks = clean(skipTasks);
        skipThreads = clean(skipThreads);
        maxHandoff = maxHandoff == null || maxHandoff.isNegative() || maxHandoff.isZero()
                ? AgentHandoffs.DEFAULT_MAX_HANDOFF
                : maxHandoff;
    }

    /** The defaults. */
    public static AgentSensorSettings defaults() {
        return new AgentSensorSettings(
                DEFAULT_SENSORS, DEFAULT_SKIP_TASKS, DEFAULT_SKIP_THREADS, AgentHandoffs.DEFAULT_MAX_HANDOFF);
    }

    /** These settings with {@code prefixes} added to the thread prefixes, once each. */
    public AgentSensorSettings withSkipThreads(List<String> prefixes) {
        List<String> threads = new ArrayList<>(skipThreads);
        threads.addAll(prefixes == null ? List.of() : prefixes);
        return new AgentSensorSettings(sensors, skipTasks, threads, maxHandoff);
    }

    /** Whether the {@code executors} sensor is asked for. */
    public boolean executors() {
        return sensors.contains(EXECUTORS);
    }

    private static List<String> clean(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                        cleaned.add(trimmed);
                    }
                }
            }
        }
        return List.copyOf(cleaned);
    }
}
