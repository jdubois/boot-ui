package io.github.jdubois.bootui.engine.javaagent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The engine's only way to the BootUI Java agent's bridge ({@code docs/PLAN-v2.md} §5.13, D33): the bridge class that
 * the agent appended to the bootstrap class path, found with {@code Class.forName(name, false, null)} and called
 * through method handles bound once. The engine never links the bridge from its own class path, which would define a
 * second, inert copy in an application or Quarkus class loader.
 *
 * <p>Nothing here throws: without a bridge, or with a bridge of another protocol, every call answers a map whose
 * {@code status} is {@value #UNAVAILABLE}, and {@link #status()} answers an empty map.
 */
public final class AgentBridgeAccess {

    /** The bridge class the agent appends to the bootstrap class path. */
    public static final String BRIDGE_CLASS = "io.github.jdubois.bootui.agent.bridge.AgentBridge";

    /** The inventory sensor's bridge class, beside the bridge ({@code docs/PLAN-v2.md} M5-3). */
    static final String CODE_INVENTORY_CLASS = "io.github.jdubois.bootui.agent.bridge.CodeInventory";

    /** The agent's never-instrumented class names, beside the bridge. */
    static final String EXCLUSIONS_CLASS = "io.github.jdubois.bootui.agent.bridge.Exclusions";

    /** The agent's transport ring, beside the bridge. */
    static final String AGENT_RING_CLASS = "io.github.jdubois.bootui.agent.bridge.AgentRing";

    /** The code-paths sensor's bridge class, beside the bridge ({@code docs/PLAN-v2.md} M5-4a). */
    static final String CODE_PATHS_CLASS = "io.github.jdubois.bootui.agent.bridge.CodePaths";

    /** Method probes' bridge class, beside the bridge ({@code docs/PLAN-v2.md} M5-8). */
    static final String METHOD_PROBES_CLASS = "io.github.jdubois.bootui.agent.bridge.MethodProbes";
    /** The side-effect sensors' bridge class, beside the bridge ({@code docs/PLAN-v2.md} M5-5a). */
    static final String SIDE_EFFECTS_CLASS = "io.github.jdubois.bootui.agent.bridge.SideEffects";
    /** The caught-exceptions sensor's bridge class, beside the bridge ({@code docs/PLAN-v2.md} M5-6a). */
    static final String CAUGHT_EXCEPTIONS_CLASS = "io.github.jdubois.bootui.agent.bridge.CaughtExceptions";

    static final String THREAD_ACTIVITY_CLASS = "io.github.jdubois.bootui.agent.bridge.ThreadActivity";

    /** The bridge protocol this engine speaks ({@code AgentBridge.PROTOCOL}). */
    public static final int EXPECTED_PROTOCOL = 1;

    static final String UNAVAILABLE = "unavailable";

    private static final AgentBridgeAccess ABSENT = new AgentBridgeAccess(null);

    private final boolean present;
    private final Integer protocol;
    private final String problem;
    private final MethodHandle attached;
    private final MethodHandle status;
    private final MethodHandle claim;
    private final MethodHandle refine;
    private final MethodHandle disarm;
    private final MethodHandle release;
    /** {@code AgentBridge.switchSensor}, or {@code null} with an agent from before runtime switches (M5-14). */
    private final MethodHandle switchSensor;

    private final Inventory inventory;
    private final CodePathsHandles codePaths;
    private final ClassEvidenceHandles classEvidence;
    private final MethodProbesHandles methodProbes;
    private final SideEffectsHandles sideEffects;
    private final CaughtHandles caught;

    /**
     * Binds the bridge's method handles. Package-private so a test can pass a bridge class its own class loader
     * defined; production code uses {@link #locate()}.
     */
    AgentBridgeAccess(Class<?> bridge) {
        Integer readProtocol = null;
        String bindProblem = null;
        MethodHandle attachedHandle = null;
        MethodHandle statusHandle = null;
        MethodHandle claimHandle = null;
        MethodHandle refineHandle = null;
        MethodHandle disarmHandle = null;
        MethodHandle releaseHandle = null;
        if (bridge != null) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                readProtocol = (Integer)
                        lookup.findStaticGetter(bridge, "PROTOCOL", int.class).invoke();
                if (readProtocol == EXPECTED_PROTOCOL) {
                    attachedHandle = lookup.findStatic(bridge, "attached", MethodType.methodType(boolean.class));
                    statusHandle = lookup.findStatic(bridge, "status", MethodType.methodType(Map.class));
                    claimHandle = lookup.findStatic(
                            bridge,
                            "claim",
                            MethodType.methodType(Map.class, Map.class, Supplier.class, Function.class));
                    refineHandle = lookup.findStatic(
                            bridge, "refine", MethodType.methodType(Map.class, long.class, Map.class));
                    disarmHandle = lookup.findStatic(bridge, "disarm", MethodType.methodType(Map.class, long.class));
                    releaseHandle = lookup.findStatic(
                            bridge, "release", MethodType.methodType(Map.class, String.class, String.class));
                } else {
                    bindProblem = "the attached BootUI agent speaks bridge protocol " + readProtocol
                            + " and this BootUI speaks protocol " + EXPECTED_PROTOCOL;
                }
            } catch (Throwable ex) {
                // ReflectiveOperationException, a LinkageError, or whatever the getter threw: never fail the caller.
                bindProblem = "the BootUI agent bridge could not be bound: " + ex;
                attachedHandle = null;
                statusHandle = null;
                claimHandle = null;
                refineHandle = null;
                disarmHandle = null;
                releaseHandle = null;
            }
        }
        this.present = bridge != null;
        this.protocol = readProtocol;
        this.problem = bindProblem;
        this.attached = attachedHandle;
        this.status = statusHandle;
        this.claim = claimHandle;
        this.refine = refineHandle;
        this.disarm = disarmHandle;
        this.release = releaseHandle;
        this.switchSensor = releaseHandle == null ? null : switchSensorHandle(bridge);
        this.inventory = releaseHandle == null ? null : Inventory.bind(bridge);
        this.codePaths = this.inventory == null ? null : CodePathsHandles.bind(bridge);
        this.classEvidence = this.inventory == null ? null : ClassEvidenceHandles.bind(bridge);
        this.methodProbes = this.inventory == null ? null : MethodProbesHandles.bind(bridge);
        this.sideEffects = this.inventory == null ? null : SideEffectsHandles.bind(bridge);
        this.caught = this.inventory == null ? null : CaughtHandles.bind(bridge);
    }

    /**
     * Whether the bridge carries the caught-exceptions sensor ({@code CaughtExceptions}): an agent of the same protocol
     * from before M5-6a does not.
     */
    public boolean caughtExceptionsSupported() {
        return inventorySupported() && caught != null;
    }

    /**
     * The caught-exceptions sensor's handler sites from id {@code from}, each {@code key, types, line, flags} joined by
     * tabs, index 0 being id {@code from}; an entry not written yet is {@code null}. Empty without the sensor's bridge.
     */
    public String[] caughtExceptionSites(int from) {
        if (!caughtExceptionsSupported()) {
            return new String[0];
        }
        try {
            Object value = caught.sites().invoke(from);
            return value instanceof String[] strings ? strings : new String[0];
        } catch (Throwable ex) {
            return new String[0];
        }
    }

    /**
     * Tells the caught-exceptions sensor that the first {@code count} requests of {@code requests}, as the agent's
     * request bits, ended ({@code CaughtExceptions.requestsEnded}); sorts the array. Does nothing without the sensor or
     * with a bridge from before M5-6a2.
     */
    public void caughtRequestsEnded(long[] requests, int count) {
        if (!caughtExceptionsSupported() || caught.requestsEnded() == null || count <= 0) {
            return;
        }
        try {
            caught.requestsEnded().invoke(requests, count);
        } catch (Throwable ex) {
            // The bridge never throws; a failure here only leaves entries pending until they expire.
        }
    }

    /** Frees the caught-exceptions sensor's stale pending entries ({@code CaughtExceptions.sweep}), at most once a second. */
    public void caughtSweep() {
        if (!caughtExceptionsSupported() || caught.sweep() == null) {
            return;
        }
        try {
            caught.sweep().invoke();
        } catch (Throwable ex) {
            // As above.
        }
    }

    /**
     * The caught-exceptions sensor's losses so far ({@code CaughtExceptions.losses}), summed, or {@code -1} when
     * unknown: without the sensor, with a bridge from before M5-6a2, or when the bridge failed to count them.
     */
    public long caughtLosses() {
        if (!caughtExceptionsSupported() || caught.losses() == null) {
            return -1L;
        }
        try {
            Object value = caught.losses().invoke();
            return value instanceof Long losses ? losses : -1L;
        } catch (Throwable ex) {
            return -1L;
        }
    }

    /**
     * The caught-exceptions sensor's entry points, bound once: its site names, and, from M5-6a2, the request ends, the
     * sweep, and the loss count, each {@code null} when the bridge predates it.
     */
    private record CaughtHandles(
            MethodHandle sites, MethodHandle requestsEnded, MethodHandle sweep, MethodHandle losses) {

        static CaughtHandles bind(Class<?> bridge) {
            MethodHandle sites;
            Class<?> caught;
            try {
                caught = Class.forName(CAUGHT_EXCEPTIONS_CLASS, false, bridge.getClassLoader());
                sites = MethodHandles.publicLookup()
                        .findStatic(caught, "sites", MethodType.methodType(String[].class, int.class));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-6a: no caught-exceptions sensor.
                return null;
            }
            return new CaughtHandles(
                    sites,
                    optional(caught, "requestsEnded", MethodType.methodType(void.class, long[].class, int.class)),
                    optional(caught, "sweep", MethodType.methodType(void.class)),
                    optional(caught, "losses", MethodType.methodType(long.class)));
        }

        private static MethodHandle optional(Class<?> caught, String name, MethodType type) {
            try {
                return MethodHandles.publicLookup().findStatic(caught, name, type);
            } catch (Throwable ex) {
                return null;
            }
        }
    }

    /**
     * The bridge on the bootstrap class path, or {@link #absent()} when the JVM runs without the BootUI agent. Only the
     * bootstrap loader is asked, so a copy any other class loader defines is never used.
     */
    public static AgentBridgeAccess locate() {
        try {
            return new AgentBridgeAccess(Class.forName(BRIDGE_CLASS, false, null));
        } catch (ClassNotFoundException | LinkageError | SecurityException ex) {
            return ABSENT;
        }
    }

    /**
     * Binds a given bridge class. Production code never calls it, since only the bootstrap loader's bridge is the
     * agent's ({@link #locate()}): adapter tests bind a stub bridge of the same protocol with it.
     */
    public static AgentBridgeAccess bind(Class<?> bridge) {
        return bridge == null ? ABSENT : new AgentBridgeAccess(bridge);
    }

    /** No bridge: the JVM runs without the BootUI agent. */
    public static AgentBridgeAccess absent() {
        return ABSENT;
    }

    /** Whether a bridge is on the bootstrap class path, compatible or not. */
    public boolean present() {
        return present;
    }

    /** The bridge's protocol, or {@code null} when no bridge is present or its protocol could not be read. */
    public Integer protocol() {
        return protocol;
    }

    /** Whether the bridge speaks {@link #EXPECTED_PROTOCOL} and every method handle is bound. */
    public boolean compatible() {
        return present && problem == null && release != null;
    }

    /** Why a present bridge cannot be used, or {@code null}. */
    public String problem() {
        return problem;
    }

    /** Whether the agent installed its handler in the bridge: false when the bridge is present but the agent failed. */
    public boolean attached() {
        if (!compatible()) {
            return false;
        }
        try {
            return (boolean) attached.invoke();
        } catch (Throwable ex) {
            return false;
        }
    }

    /** The bridge's status ({@code AgentBridge.status()}), or an empty map without a compatible bridge. */
    public Map<String, Object> status() {
        if (!compatible()) {
            return Map.of();
        }
        try {
            return copy(status.invoke());
        } catch (Throwable ex) {
            return Map.of();
        }
    }

    /**
     * Claims the agent for one application run. The bridge holds {@code capture} and {@code reopen} weakly, so the
     * caller keeps them strongly reachable for the run and passes fresh objects for each claim.
     */
    public Map<String, Object> claim(
            Map<String, ?> request, Supplier<Object> capture, Function<Object, AutoCloseable> reopen) {
        if (!compatible()) {
            return unavailable();
        }
        try {
            return copy(claim.invoke(request, capture, reopen));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /** Adds packages to the claim the token identifies. */
    public Map<String, Object> refine(long token, Map<String, ?> request) {
        if (!compatible()) {
            return unavailable();
        }
        try {
            return copy(refine.invoke(token, request));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /** Ends the run the token identifies; the agent keeps its transformers (D34). */
    public Map<String, Object> disarm(long token) {
        if (!compatible()) {
            return unavailable();
        }
        try {
            return copy(disarm.invoke(token));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /** Removes the agent's transformers because BootUI is disabled for this application, unless another holds it. */
    public Map<String, Object> release(String application, String mode) {
        if (!compatible()) {
            return unavailable();
        }
        try {
            return copy(release.invoke(application, mode));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /**
     * Whether the bridge switches sensors at run time ({@code AgentBridge.switchSensor}, {@code docs/PLAN-v2.md}
     * M5-14): an agent of the same protocol from before it does not.
     */
    public boolean sensorSwitchSupported() {
        return compatible() && switchSensor != null;
    }

    /**
     * Switches {@code sensor} on or off at run time for the claim the token identifies, and for the later claims of its
     * slot until the JVM ends. Answers {@value #UNAVAILABLE} without a bridge that supports it.
     */
    public Map<String, Object> switchSensor(long token, String sensor, boolean enabled) {
        if (!sensorSwitchSupported()) {
            return compatible()
                    ? unavailable("the attached BootUI agent predates runtime sensor switches")
                    : unavailable();
        }
        try {
            return copy(switchSensor.invoke(token, sensor, enabled));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    private static MethodHandle switchSensorHandle(Class<?> bridge) {
        try {
            return MethodHandles.publicLookup()
                    .findStatic(
                            bridge,
                            "switchSensor",
                            MethodType.methodType(Map.class, long.class, String.class, boolean.class));
        } catch (Throwable ex) {
            // An agent of this protocol from before M5-14: its sensors change only with a new claim.
            return null;
        }
    }

    /**
     * Whether the bridge carries the inventory sensor's entry points ({@code CodeInventory}, {@code AgentRing}, and
     * {@code AgentBridge.bootUiWork}): an agent of the same protocol from before M5-3 does not.
     */
    public boolean inventorySupported() {
        return compatible() && inventory != null;
    }

    /**
     * The inventory sensor's state for the run of claim {@code generation} ({@code CodeInventory.snapshot}): copies of
     * its executed and late bitsets and tracking states, or {@code null} without the sensor's bridge, before any claim
     * asked for it, or when the current run belongs to another generation.
     */
    public Map<String, Object> inventorySnapshot(long generation) {
        if (!inventorySupported()) {
            return null;
        }
        try {
            Object value = inventory.snapshot.invoke(generation);
            return value == null ? null : copy(value);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * A number that changes whenever the inventory sensor's snapshot may have ({@code CodeInventory.version}): a cheap
     * fingerprint, -1 without the sensor's bridge.
     */
    public long inventoryVersion() {
        if (!inventorySupported()) {
            return -1L;
        }
        try {
            return (long) inventory.version.invoke();
        } catch (Throwable ex) {
            return -1L;
        }
    }

    /**
     * Whether the attached agent never instruments the class {@code binaryName} by name ({@code Exclusions.excluded}):
     * BootUI's own, the JDK's, and generated proxies. False without the sensor's bridge.
     */
    public boolean excluded(String binaryName) {
        if (!inventorySupported()) {
            return false;
        }
        try {
            return (boolean) inventory.excluded.invoke(binaryName);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** The method keys of ids {@code from} to {@code from + max - 1}, as far as they exist: a copy. Never null. */
    public String[] methodKeys(int from, int max) {
        if (!inventorySupported()) {
            return new String[0];
        }
        try {
            Object value = inventory.methodKeys.invoke(from, max);
            return value instanceof String[] keys ? keys : new String[0];
        } catch (Throwable ex) {
            return new String[0];
        }
    }

    /** Every code source the inventory sensor counted, with its counters: copies. Never null. */
    public List<Map<String, Object>> codeSources() {
        if (!inventorySupported()) {
            return List.of();
        }
        try {
            Object value = inventory.codeSources.invoke();
            List<Map<String, Object>> sources = new java.util.ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?>) {
                        sources.add(copy(item));
                    }
                }
            }
            return sources;
        } catch (Throwable ex) {
            return List.of();
        }
    }

    /**
     * Drains the agent's ring into {@code sink} with the claim token {@code token}: only the current claim's token
     * drains, one caller at a time. Returns how many records were drained, 0 without the ring.
     */
    public int drain(long token, Consumer<long[]> sink) {
        if (!inventorySupported()) {
            return 0;
        }
        try {
            return (int) inventory.drain.invoke(token, sink);
        } catch (Throwable ex) {
            return 0;
        }
    }

    /**
     * The strings the ring's records of claim {@code generation} refer to, from id {@code from}, or {@code null} when
     * the intern table belongs to another generation.
     */
    public String[] interned(long generation, int from) {
        if (!inventorySupported()) {
            return null;
        }
        try {
            Object value = inventory.interned.invoke(generation, from);
            return value instanceof String[] strings ? strings : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Marks or unmarks the calling thread's work as BootUI's own, so the inventory sensor does not count what it loads:
     * returns the previous mark. Does nothing without the sensor's bridge.
     */
    public boolean bootUiWork(boolean on) {
        if (!inventorySupported()) {
            return false;
        }
        try {
            return (boolean) inventory.bootUiWork.invoke(on);
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * Whether the bridge carries the code-paths sensor's entry points ({@code CodePaths}): an agent of the same protocol
     * from before M5-4 does not, and then the sensor is simply unavailable.
     */
    public boolean codePathsSupported() {
        return inventorySupported() && codePaths != null;
    }

    /**
     * Hands the code-paths sensor's queued fragments to {@code sink} with the claim token {@code token}: only the
     * current claim's token drains, one caller at a time. The sink owns each {@code long[]} blob. Returns how many were
     * drained, 0 without the sensor's bridge.
     */
    public int drainCodePaths(long token, Consumer<long[]> sink) {
        if (!codePathsSupported()) {
            return 0;
        }
        try {
            return (int) codePaths.drain.invoke(token, sink);
        } catch (Throwable ex) {
            return 0;
        }
    }

    /**
     * Excludes method {@code id} from the code-paths sensor for the rest of the run of the claim {@code token}: its
     * time then stays in its caller. Returns whether it is excluded; false without the sensor's bridge.
     */
    public boolean excludeCodePathsMethod(long token, int id) {
        if (!codePathsSupported()) {
            return false;
        }
        try {
            return (boolean) codePaths.exclude.invoke(token, id);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** The method ids the code-paths sensor excludes in this run: a copy, empty without the sensor's bridge. */
    public int[] codePathsExcluded() {
        if (!codePathsSupported()) {
            return new int[0];
        }
        try {
            Object value = codePaths.excluded.invoke();
            return value instanceof int[] ids ? ids : new int[0];
        } catch (Throwable ex) {
            return new int[0];
        }
    }

    /**
     * Whether the bridge keeps class-name evidence ({@code CodeInventory.classEvidence} and {@code classLoads}): an
     * agent of the same protocol from before M5-9a does not, and then no reach can be told from it.
     */
    public boolean classEvidenceSupported() {
        return inventorySupported() && classEvidence != null;
    }

    /**
     * How complete the inventory sensor's class-name evidence is ({@code CodeInventory.classEvidence}): a copy, or
     * {@code null} without it or on failure.
     */
    public Map<String, Object> classEvidence() {
        if (!classEvidenceSupported()) {
            return null;
        }
        try {
            Object value = classEvidence.evidence.invoke();
            return value instanceof Map<?, ?> ? copy(value) : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Whether the bridge carries the side-effect sensors' entry points ({@code SideEffects}): an agent of the same
     * protocol from before M5-5a does not, and then Side Effects is simply unavailable.
     */
    public boolean sideEffectsSupported() {
        return inventorySupported() && sideEffects != null;
    }

    /**
     * Drains the side-effect sensors' ring into {@code sink} with the claim token {@code token}: only the current
     * claim's token drains, one caller at a time. The sink receives one reused {@code long[]} per record and copies what
     * it keeps. Returns how many records were drained, 0 without the sensors' bridge.
     */
    public int drainSideEffects(long token, Consumer<long[]> sink) {
        if (!sideEffectsSupported()) {
            return 0;
        }
        try {
            return (int) sideEffects.drain.invoke(token, sink);
        } catch (Throwable ex) {
            return 0;
        }
    }

    /**
     * Tells the thread-activity and resources sensors that request {@code request} of claim {@code generation} ended,
     * its response complete (M5-5e, M5-5g): the bridge hands the end to each that is on, whose drain checks what the
     * request left running or open. Takes no lock; does nothing with a bridge from before M5-5e.
     */
    public void threadActivityRequestEnded(long generation, long request) {
        if (!sideEffectsSupported() || sideEffects.requestEnded() == null) {
            return;
        }
        try {
            sideEffects.requestEnded().invoke(generation, request);
        } catch (Throwable ex) {
            // Its threads are not checked at its end.
        }
    }

    /** Whether the bridge carries the thread-activity sensor (M5-5e). */
    public boolean threadActivitySupported() {
        return sideEffectsSupported() && sideEffects.requestEnded() != null;
    }

    /**
     * Tells the bridge the recording of claim {@code generation} was cleared, so the files and environment sensors'
     * intern quotas count again; does nothing with a bridge from before M5-5d.
     */
    public void sideEffectsRecordingCleared(long generation) {
        if (!sideEffectsSupported() || sideEffects.recordingCleared() == null) {
            return;
        }
        try {
            sideEffects.recordingCleared().invoke(generation);
        } catch (Throwable ex) {
            // The quotas stay as they were.
        }
    }

    /**
     * Publishes the calling thread's buffered side-effect records to the ring now, as a thread's table is otherwise
     * flushed only by its next record, a scope change, or when full ({@code docs/PLAN-v2.md} M5-7b). Does nothing with
     * a bridge without it. Never throws.
     */
    public void sideEffectsFlushThread() {
        if (!sideEffectsSupported() || sideEffects.flushThread() == null) {
            return;
        }
        try {
            sideEffects.flushThread().invoke();
        } catch (Throwable ex) {
            // The records stay buffered until the thread's next record.
        }
    }

    /** Internal scope ownership, never status counters; missing older-agent metadata is explicitly unknown. */
    public Map<String, Object> sideEffectsPending(long token) {
        if (!sideEffectsSupported() || sideEffects.pending() == null) {
            return Map.of("unknownReason", "the agent has no pending side-effect ownership metadata");
        }
        try {
            Object value = sideEffects.pending().invoke(token);
            return value instanceof Map<?, ?>
                    ? copy(value)
                    : Map.of("unknownReason", "the agent's pending ownership response was malformed");
        } catch (Throwable ex) {
            return Map.of("unknownReason", "the agent's pending side-effect ownership could not be read");
        }
    }

    /**
     * The strings the side-effect records of claim {@code generation} refer to, from id {@code from}, or {@code null}
     * when their table belongs to another generation or without the sensors' bridge.
     */
    public String[] sideEffectsInterned(long generation, int from) {
        if (!sideEffectsSupported()) {
            return null;
        }
        try {
            Object value = sideEffects.interned.invoke(generation, from);
            return value instanceof String[] strings ? strings : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * When the classes {@code binaryNames} of code source {@code sourceId} last loaded, and by whom
     * ({@code CodeInventory.classLoads}): two values per name, or {@code null} without the evidence, for an unknown
     * code source, or on failure.
     */
    public long[] classLoads(int sourceId, String[] binaryNames) {
        if (!classEvidenceSupported()) {
            return null;
        }
        try {
            Object value = classEvidence.loads.invoke(sourceId, binaryNames);
            return value instanceof long[] loads && loads.length == binaryNames.length * 2 ? loads : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /** The class-name evidence's bridge entry points, bound once; {@code null} when the bridge has none. */
    private record ClassEvidenceHandles(MethodHandle evidence, MethodHandle loads) {

        static ClassEvidenceHandles bind(Class<?> bridge) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> codeInventory = Class.forName(CODE_INVENTORY_CLASS, false, bridge.getClassLoader());
                return new ClassEvidenceHandles(
                        lookup.findStatic(codeInventory, "classEvidence", MethodType.methodType(Map.class)),
                        lookup.findStatic(
                                codeInventory,
                                "classLoads",
                                MethodType.methodType(long[].class, int.class, String[].class)));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-9a: no class-name evidence.
                return null;
            }
        }
    }

    /**
     * Whether the bridge carries method probes' entry points ({@code MethodProbes}): an agent of the same protocol from
     * before M5-8 does not, and probes are then unavailable.
     */
    public boolean methodProbesSupported() {
        return inventorySupported() && methodProbes != null;
    }

    /**
     * Whether the bridge's method probes can record argument and return shapes ({@code docs/PLAN-v2.md} M5-8, D44): an
     * agent from before them starts every probe metadata-only.
     */
    public boolean methodProbeShapesSupported() {
        return methodProbesSupported() && methodProbes.shapes();
    }

    /**
     * Starts a method probe for the claim {@code token} ({@code MethodProbes.start}): the answer's {@code status} is
     * {@code started} with the {@code probe}, or {@code invalid}, {@code refused}, {@code stale}, {@code unavailable}, or
     * {@code failed} with a {@code reason}.
     */
    public Map<String, Object> startMethodProbe(long token, Map<String, ?> request) {
        if (!methodProbesSupported()) {
            return unavailable(present ? "this BootUI agent predates method probes" : null);
        }
        try {
            return copy(methodProbes.start.invoke(token, request));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /** Stops the method probe {@code id} for the claim {@code token} ({@code MethodProbes.stop}). */
    public Map<String, Object> stopMethodProbe(long token, long id) {
        if (!methodProbesSupported()) {
            return unavailable(present ? "this BootUI agent predates method probes" : null);
        }
        try {
            return copy(methodProbes.stop.invoke(token, id));
        } catch (Throwable ex) {
            return failed(ex);
        }
    }

    /** Every method probe the bridge keeps, as maps of JDK types: copies. Never null. */
    public List<Map<String, Object>> methodProbes() {
        if (!methodProbesSupported()) {
            return List.of();
        }
        try {
            Object value = methodProbes.list.invoke();
            List<Map<String, Object>> probes = new java.util.ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?>) {
                        probes.add(copy(item));
                    }
                }
            }
            return probes;
        } catch (Throwable ex) {
            return List.of();
        }
    }

    /** Method probes' bridge entry points, bound once; {@code null} when the bridge has none. */
    private record MethodProbesHandles(MethodHandle start, MethodHandle stop, MethodHandle list, boolean shapes) {

        static MethodProbesHandles bind(Class<?> bridge) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> probes = Class.forName(METHOD_PROBES_CLASS, false, bridge.getClassLoader());
                return new MethodProbesHandles(
                        lookup.findStatic(probes, "start", MethodType.methodType(Map.class, long.class, Map.class)),
                        lookup.findStatic(probes, "stop", MethodType.methodType(Map.class, long.class, long.class)),
                        lookup.findStatic(probes, "list", MethodType.methodType(List.class)),
                        shapes(lookup, probes));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-8: no method probes.
                return null;
            }
        }

        /** Whether the bridge records argument and return shapes: an agent from before D44 has no such constant. */
        private static boolean shapes(MethodHandles.Lookup lookup, Class<?> probes) {
            try {
                Object protocol = lookup.findStaticGetter(probes, "SHAPES_PROTOCOL", int.class)
                        .invoke();
                return protocol instanceof Integer version && version >= 1;
            } catch (Throwable ex) {
                return false;
            }
        }
    }

    /** The side-effect sensors' bridge entry points, bound once; {@code null} when the bridge has none. */
    private record SideEffectsHandles(
            MethodHandle drain,
            MethodHandle interned,
            MethodHandle recordingCleared,
            MethodHandle flushThread,
            MethodHandle requestEnded,
            MethodHandle pending) {

        static SideEffectsHandles bind(Class<?> bridge) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> sideEffects = Class.forName(SIDE_EFFECTS_CLASS, false, bridge.getClassLoader());
                return new SideEffectsHandles(
                        lookup.findStatic(
                                sideEffects, "drain", MethodType.methodType(int.class, long.class, Consumer.class)),
                        lookup.findStatic(
                                sideEffects, "interned", MethodType.methodType(String[].class, long.class, int.class)),
                        recordingCleared(lookup, sideEffects),
                        flushThread(lookup, sideEffects),
                        requestEnded(lookup, bridge),
                        pending(lookup, sideEffects));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-5a: no side-effect sensors.
                return null;
            }
        }

        private static MethodHandle pending(MethodHandles.Lookup lookup, Class<?> sideEffects) {
            try {
                return lookup.findStatic(sideEffects, "pending", MethodType.methodType(Map.class, long.class));
            } catch (ReflectiveOperationException | LinkageError ex) {
                return null;
            }
        }

        /** {@code ThreadActivity.requestEnded(long, long)}, or {@code null} for a bridge from before M5-5e. */
        private static MethodHandle requestEnded(MethodHandles.Lookup lookup, Class<?> bridge) {
            try {
                Class<?> threadActivity = Class.forName(THREAD_ACTIVITY_CLASS, false, bridge.getClassLoader());
                return lookup.findStatic(
                        threadActivity, "requestEnded", MethodType.methodType(void.class, long.class, long.class));
            } catch (ReflectiveOperationException | LinkageError ex) {
                return null;
            }
        }

        /** {@code SideEffects.recordingCleared(long)}, or {@code null} for a bridge from before M5-5d. */
        private static MethodHandle recordingCleared(MethodHandles.Lookup lookup, Class<?> sideEffects) {
            try {
                return lookup.findStatic(
                        sideEffects, "recordingCleared", MethodType.methodType(void.class, long.class));
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }

        /** {@code SideEffects.flushThread()}, or {@code null} for a bridge without it. */
        private static MethodHandle flushThread(MethodHandles.Lookup lookup, Class<?> sideEffects) {
            try {
                return lookup.findStatic(sideEffects, "flushThread", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }

    /** The code-paths sensor's bridge entry points, bound once; {@code null} when the bridge has none. */
    private record CodePathsHandles(MethodHandle drain, MethodHandle exclude, MethodHandle excluded) {

        static CodePathsHandles bind(Class<?> bridge) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> codePaths = Class.forName(CODE_PATHS_CLASS, false, bridge.getClassLoader());
                return new CodePathsHandles(
                        lookup.findStatic(
                                codePaths, "drain", MethodType.methodType(int.class, long.class, Consumer.class)),
                        lookup.findStatic(
                                codePaths, "exclude", MethodType.methodType(boolean.class, long.class, int.class)),
                        lookup.findStatic(codePaths, "excluded", MethodType.methodType(int[].class)));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-4: no code-paths sensor.
                return null;
            }
        }
    }

    /** The inventory sensor's bridge entry points, bound once; {@code null} when the bridge has none. */
    private record Inventory(
            MethodHandle snapshot,
            MethodHandle methodKeys,
            MethodHandle codeSources,
            MethodHandle drain,
            MethodHandle interned,
            MethodHandle bootUiWork,
            MethodHandle version,
            MethodHandle excluded) {

        static Inventory bind(Class<?> bridge) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                ClassLoader loader = bridge.getClassLoader();
                Class<?> codeInventory = Class.forName(CODE_INVENTORY_CLASS, false, loader);
                Class<?> ring = Class.forName(AGENT_RING_CLASS, false, loader);
                Class<?> exclusions = Class.forName(EXCLUSIONS_CLASS, false, loader);
                return new Inventory(
                        lookup.findStatic(codeInventory, "snapshot", MethodType.methodType(Map.class, long.class)),
                        lookup.findStatic(
                                codeInventory,
                                "methodKeys",
                                MethodType.methodType(String[].class, int.class, int.class)),
                        lookup.findStatic(codeInventory, "codeSources", MethodType.methodType(List.class)),
                        lookup.findStatic(ring, "drain", MethodType.methodType(int.class, long.class, Consumer.class)),
                        lookup.findStatic(
                                ring, "interned", MethodType.methodType(String[].class, long.class, int.class)),
                        lookup.findStatic(bridge, "bootUiWork", MethodType.methodType(boolean.class, boolean.class)),
                        lookup.findStatic(codeInventory, "version", MethodType.methodType(long.class)),
                        lookup.findStatic(exclusions, "excluded", MethodType.methodType(boolean.class, String.class)));
            } catch (Throwable ex) {
                // An agent of this protocol from before M5-3: no inventory sensor.
                return null;
            }
        }
    }

    private Map<String, Object> unavailable() {
        return unavailable(null);
    }

    private Map<String, Object> unavailable(String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", UNAVAILABLE);
        map.put("reason", reason != null ? reason : present ? problem : "the JVM runs without the BootUI agent");
        return map;
    }

    private static Map<String, Object> failed(Throwable ex) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "failed");
        map.put("reason", "the BootUI agent bridge failed: " + ex);
        return map;
    }

    /** A shallow, ordered copy with string keys; the bridge answers JDK types only. */
    private static Map<String, Object> copy(Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
    }

    /** Reads {@code key} of a bridge map as a map, or an empty map. */
    public static Map<String, Object> map(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Map<?, ?> ? copy(value) : Map.of();
    }

    /** Reads {@code key} of a bridge map as a string, or {@code null}. */
    static String text(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads {@code key} of a bridge map as a number, or {@code null}. */
    public static Long number(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** Reads {@code key} of a bridge map as a boolean, false when absent. */
    static boolean flag(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Boolean bool && bool;
    }

    /** Reads {@code key} of a bridge map as a collection, or an empty one. */
    static Collection<?> items(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Collection<?> collection ? collection : List.of();
    }
}
