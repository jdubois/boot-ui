package io.github.jdubois.bootui.engine.javaagent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private Map<String, Object> unavailable() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", UNAVAILABLE);
        map.put("reason", present ? problem : "the JVM runs without the BootUI agent");
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
    static Map<String, Object> map(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Map<?, ?> ? copy(value) : Map.of();
    }

    /** Reads {@code key} of a bridge map as a string, or {@code null}. */
    static String text(Map<String, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads {@code key} of a bridge map as a number, or {@code null}. */
    static Long number(Map<String, ?> source, String key) {
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
