package io.github.jdubois.bootui.engine.javaagent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What this application asks the BootUI agent's sensors to do ({@code docs/PLAN-v2.md} M5-2, M5-3): which sensors to
 * install ({@code bootui.agent.sensors}), for the {@code executors} and {@code threads} sensors which tasks and threads
 * to leave alone because they already propagate their context ({@code bootui.agent.executors.skip-tasks},
 * {@code bootui.agent.executors.skip-threads}) and how long a handoff's work is attributed to its request
 * ({@code bootui.agent.executors.max-handoff}), and the capacity of the agent's transport ring
 * ({@code bootui.agent.ring-capacity}).
 *
 * @param sensors the sensors to install: {@code executors} and {@code inventory}, and the opt-in {@code threads}
 * @param skipTasks task class-name prefixes the propagation sensors never propagate
 * @param skipThreads thread-name prefixes the propagation sensors never propagate to
 * @param maxHandoff how long a handoff's work is attributed to its request
 * @param ringCapacity the records the agent's transport ring holds, clamped to {@value #MIN_RING_CAPACITY} to
 *     {@value #MAX_RING_CAPACITY} and rounded up to a power of two
 */
public record AgentSensorSettings(
        List<String> sensors, List<String> skipTasks, List<String> skipThreads, Duration maxHandoff, int ringCapacity) {

    /** The executors sensor. */
    public static final String EXECUTORS = "executors";

    /**
     * The sensor carrying a request into threads started from its work and into virtual threads (M5-2c). Not in the
     * defaults: {@code java.lang.Thread} is the riskiest class to retransform, so an application asks for it.
     */
    public static final String THREADS = "threads";

    /**
     * The sensor recording which application methods ran in this run and which code sources loaded classes (M5-3), on
     * by default (D21).
     */
    public static final String INVENTORY = "inventory";

    /** The default {@code bootui.agent.sensors}. */
    public static final List<String> DEFAULT_SENSORS = List.of(EXECUTORS, INVENTORY);

    /** The default {@code bootui.agent.ring-capacity}: records of 64 bytes, so 4 MB. */
    public static final int DEFAULT_RING_CAPACITY = 65_536;

    /** The smallest transport ring. */
    public static final int MIN_RING_CAPACITY = 1_024;

    /** The largest transport ring: 256 MB of records. */
    public static final int MAX_RING_CAPACITY = 4_194_304;

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
        ringCapacity = ringCapacity(ringCapacity);
    }

    /** These settings with the default ring capacity. */
    public AgentSensorSettings(
            List<String> sensors, List<String> skipTasks, List<String> skipThreads, Duration maxHandoff) {
        this(sensors, skipTasks, skipThreads, maxHandoff, DEFAULT_RING_CAPACITY);
    }

    /** The defaults. */
    public static AgentSensorSettings defaults() {
        return new AgentSensorSettings(
                DEFAULT_SENSORS,
                DEFAULT_SKIP_TASKS,
                DEFAULT_SKIP_THREADS,
                AgentHandoffs.DEFAULT_MAX_HANDOFF,
                DEFAULT_RING_CAPACITY);
    }

    /**
     * {@code requested} as the agent sizes its ring: the default when not positive, else clamped to
     * {@value #MIN_RING_CAPACITY} to {@value #MAX_RING_CAPACITY} and rounded up to a power of two.
     */
    public static int ringCapacity(int requested) {
        if (requested <= 0) {
            return DEFAULT_RING_CAPACITY;
        }
        int clamped = Math.max(MIN_RING_CAPACITY, Math.min(MAX_RING_CAPACITY, requested));
        int power = Integer.highestOneBit(clamped);
        return power == clamped ? clamped : power << 1;
    }

    /** These settings with {@code prefixes} added to the thread prefixes, once each. */
    public AgentSensorSettings withSkipThreads(List<String> prefixes) {
        List<String> threads = new ArrayList<>(skipThreads);
        threads.addAll(prefixes == null ? List.of() : prefixes);
        return new AgentSensorSettings(sensors, skipTasks, threads, maxHandoff, ringCapacity);
    }

    /** Whether the {@code inventory} sensor is asked for. */
    public boolean inventory() {
        return sensors.contains(INVENTORY);
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
