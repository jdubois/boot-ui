package io.github.jdubois.bootui.engine.javaagent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What this application asks the BootUI agent's sensors to do ({@code docs/PLAN-v2.md} M5-2, M5-3, M5-4a, M5-5): which sensors to
 * install ({@code bootui.agent.sensors}), for the {@code executors} and {@code threads} sensors which tasks and threads
 * to leave alone because they already propagate their context ({@code bootui.agent.executors.skip-tasks},
 * {@code bootui.agent.executors.skip-threads}) and how long a handoff's work is attributed to its request
 * ({@code bootui.agent.executors.max-handoff}), and the capacity of the agent's transport ring
 * ({@code bootui.agent.ring-capacity}).
 *
 * @param sensors the sensors to install: {@code executors}, {@code inventory}, {@code code-paths}, {@code processes},
 *     {@code network}, and {@code blocking}, and the opt-in {@code threads}, {@code files}, {@code environment}, and
 *     {@code thread-activity}, and {@code caught-exceptions}; the Side Effects sensors this version does not ship are
 *     accepted ({@link #NOT_AVAILABLE_SENSORS}), and any other id is rejected
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

    /**
     * The sensor timing the application's bean methods per request (M5-4a), on by default (D21): it shares the
     * inventory sensor's transformer.
     */
    public static final String CODE_PATHS = "code-paths";

    /**
     * The Side Effects sensor recording the processes the application starts (M5-5a, §5.16), on by default: one hook
     * on {@code ProcessBuilder.start}, which costs nothing beside spawning a process.
     */
    public static final String PROCESSES = "processes";

    /**
     * The Side Effects sensor recording the hosts the application connects to, the datagrams it sends, and the names the
     * JVM resolves (M5-5b, §5.16), on by default: rare hooks on connects and cache-missing lookups, datagram sends
     * counted per thread; never a byte sent or received.
     */
    public static final String NETWORK = "network";

    /**
     * The Side Effects sensor recording the files the application reads and writes (M5-5d, §5.16), never contents: path
     * patterns, with class loading, the JDK's own files, and logging appenders grouped apart; opt-in (D37), as its
     * cumulative overhead on the benchmark's I/O route reached the 10 % budget.
     */
    public static final String FILES = "files";

    /**
     * The Side Effects sensor recording the environment variables and system properties the application reads by name
     * (M5-5d, §5.16), never their values; opt-in (D37), as it advises {@code System.getProperty}, which frameworks call
     * often.
     */
    public static final String ENVIRONMENT = "environment";

    /**
     * The Side Effects sensor recording {@code Thread.sleep}, {@code Object.wait}, {@code LockSupport.park}, and the
     * network and files sensors' blocking operations started on an event loop (M5-5c, §5.16), on by default: off event
     * loops, its {@code park} hook returns after one volatile read until an adapter registered a loop, then after one
     * table lookup.
     */
    public static final String BLOCKING = "blocking";

    /**
     * The Side Effects sensor recording the threads the application starts and the executors it creates per route,
     * and those a request's application code left running when it ended (M5-5e, §5.16), never a thread-local or a
     * task. Distinct from {@link #THREADS}, which carries a request's context into threads. Opt-in until a same-runner
     * A/B of the agent's overhead benchmark shows its own increment at most 3 % and the cumulative overhead at most 10 %.
     */
    public static final String THREAD_ACTIVITY = "thread-activity";

    /**
     * The sensor reporting the exceptions application code catches (M5-6a): opt-in until its overhead is measured
     * against the default sensors' budget (D21, D37).
     */
    public static final String CAUGHT_EXCEPTIONS = "caught-exceptions";

    /** The Side Effects sensors this version ships. */
    public static final List<String> SIDE_EFFECT_SENSORS =
            List.of(PROCESSES, NETWORK, FILES, ENVIRONMENT, BLOCKING, THREAD_ACTIVITY);

    /** Every sensor id this version installs. */
    public static final List<String> KNOWN_SENSORS = List.of(
            EXECUTORS,
            THREADS,
            INVENTORY,
            CODE_PATHS,
            PROCESSES,
            NETWORK,
            FILES,
            ENVIRONMENT,
            BLOCKING,
            THREAD_ACTIVITY,
            CAUGHT_EXCEPTIONS);

    /**
     * The Side Effects sensors the panel lists but this version does not ship ({@code docs/PLAN-v2.md} §5.16):
     * {@code bootui.agent.sensors} accepts them, with a warning, and the panel reports them not available.
     */
    public static final List<String> NOT_AVAILABLE_SENSORS = List.of("thread-locals", "resources", "security-sinks");

    /** The default {@code bootui.agent.sensors}. */
    public static final List<String> DEFAULT_SENSORS =
            List.of(EXECUTORS, INVENTORY, CODE_PATHS, PROCESSES, NETWORK, BLOCKING);

    /**
     * The sensors this version ships off by default, which the Java Agent and Side Effects panels switch on and off at
     * run time ({@code docs/PLAN-v2.md} M5-14). {@code caught-exceptions} (M5-6a), also off by default, is not switched at
     * run time: its visit of every application class is installed with the claim only.
     */
    public static final List<String> OPT_IN_SENSORS = List.of(THREADS, FILES, ENVIRONMENT);

    /**
     * Why {@code id}, one of {@link #OPT_IN_SENSORS}, is off by default, as the panels show it beside its switch; or
     * {@code null} for any other sensor.
     */
    public static String optInReason(String id) {
        if (id == null) {
            return null;
        }
        return switch (id) {
            case THREADS ->
                "Off by default: it retransforms java.lang.Thread, the riskiest JDK class to instrument, and a failed"
                        + " self-test leaves it off until the application restarts.";
            case FILES ->
                "Off by default: with the default sensors, the agent's overhead on the benchmark's I/O route measured"
                        + " about 10.6 %, over its 10 % budget.";
            case ENVIRONMENT ->
                "Off by default: it advises System.getProperty, which frameworks call often; a read takes about 23–28 ns"
                        + " with it instead of 5–6 ns.";
            default -> null;
        };
    }

    /** The default {@code bootui.agent.ring-capacity}: records of 64 bytes, so 4 MB. */
    public static final int DEFAULT_RING_CAPACITY = 65_536;

    /** The smallest transport ring. */
    public static final int MIN_RING_CAPACITY = 1_024;

    /** The largest transport ring: 256 MB of records. */
    public static final int MAX_RING_CAPACITY = 4_194_304;

    /**
     * The default {@code bootui.agent.executors.skip-tasks}: BootUI's own and Micrometer's context-propagating wrappers,
     * Spring's context-propagating task decorator, and JDK, connection-pool, and cache internals (Caffeine runs its
     * maintenance on the common pool; the JDK reaps a started process on its own pool).
     */
    public static final List<String> DEFAULT_SKIP_TASKS = List.of(
            "io.github.jdubois.bootui.engine.correlation.ManagedTasks",
            "io.micrometer.context.",
            "org.springframework.core.task.support.ContextPropagatingTaskDecorator",
            "jdk.internal.",
            "sun.",
            // The JDK's process reaper, which waits for a process a request started: plumbing, not the request's work.
            "java.lang.ProcessHandleImpl",
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
        for (String sensor : sensors) {
            if (!KNOWN_SENSORS.contains(sensor) && !NOT_AVAILABLE_SENSORS.contains(sensor)) {
                throw new IllegalArgumentException("bootui.agent.sensors names an unknown sensor '" + sensor
                        + "': this version installs " + String.join(", ", KNOWN_SENSORS)
                        + ", and Side Effects also lists " + String.join(", ", NOT_AVAILABLE_SENSORS)
                        + ", which are not available in this version.");
            }
        }
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

    /** Whether the {@code code-paths} sensor is asked for. */
    public boolean codePaths() {
        return sensors.contains(CODE_PATHS);
    }

    /** The sensors asked for that this version lists but does not ship: accepted, and to be warned about. */
    public List<String> notAvailable() {
        return sensors.stream().filter(NOT_AVAILABLE_SENSORS::contains).toList();
    }

    /** The warning for {@link #notAvailable()}, or {@code null} when none is asked for. */
    public String notAvailableWarning() {
        List<String> asked = notAvailable();
        return asked.isEmpty()
                ? null
                : "bootui.agent.sensors asks for " + String.join(", ", asked)
                        + ", which this version of the BootUI agent does not ship yet: the Side Effects panel reports"
                        + (asked.size() == 1 ? " it" : " them") + " not available.";
    }

    /** Whether the {@code caught-exceptions} sensor is asked for. */
    public boolean caughtExceptions() {
        return sensors.contains(CAUGHT_EXCEPTIONS);
    }

    /** Whether the {@code processes} sensor is asked for. */
    public boolean processes() {
        return sensors.contains(PROCESSES);
    }

    /** Whether the {@code network} sensor is asked for. */
    public boolean network() {
        return sensors.contains(NETWORK);
    }

    /** Whether the {@code files} sensor is asked for. */
    public boolean files() {
        return sensors.contains(FILES);
    }

    /** Whether the opt-in {@code environment} sensor is asked for. */
    public boolean environment() {
        return sensors.contains(ENVIRONMENT);
    }

    /** Whether the {@code blocking} sensor is asked for. */
    public boolean blocking() {
        return sensors.contains(BLOCKING);
    }

    /** Whether the {@code thread-activity} sensor is asked for. */
    public boolean threadActivity() {
        return sensors.contains(THREAD_ACTIVITY);
    }

    /** Whether any Side Effects sensor is asked for. */
    public boolean sideEffects() {
        return sensors.stream().anyMatch(SIDE_EFFECT_SENSORS::contains);
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
