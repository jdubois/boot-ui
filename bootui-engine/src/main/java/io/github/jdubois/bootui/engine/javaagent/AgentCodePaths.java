package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The adapters' hooks into the BootUI agent's {@code code-paths} sensor ({@code docs/PLAN-v2.md} §5.14, M5-4a): where an
 * adapter opens and closes a request's scope on the handling thread ({@link #begin()}, {@link #end()}), so the sensor's
 * fragment covers the request apart from application filters outside it, and where it marks the request's phase
 * ({@link #phase}) and where the request is over on a thread no {@link #end()} closes ({@link #clearPhase()}), so each
 * method node records the phase it entered in; and where a recorder stamps the SQL statement, REST client call, cache
 * access, or AI call it records with the innermost open node ({@link #stamp()}, M5-4c). Each call is one method handle
 * bound once to the bridge on the bootstrap class path; without the agent, or with one predating the sensor or its
 * stamps, every call does nothing and a stamp is 0. Never throws.
 */
public final class AgentCodePaths {

    private static volatile Handles handles = Handles.locate();

    /** The most recent requests remembered as assembly only. */
    static final int ASSEMBLY_ONLY_REQUESTS = 4_096;

    /**
     * The requests marked assembly only, looked up by id without a global lock; {@link #ASSEMBLY_ORDER} evicts the
     * oldest past {@value #ASSEMBLY_ONLY_REQUESTS}.
     */
    private static final Map<String, Boolean> ASSEMBLY_ONLY = new ConcurrentHashMap<>();

    private static final Queue<String> ASSEMBLY_ORDER = new ConcurrentLinkedQueue<>();

    private static final AtomicInteger ASSEMBLY_SIZE = new AtomicInteger();

    private AgentCodePaths() {}

    /** A request's scope opened on this thread. */
    public static void begin() {
        MethodHandle begin = handles.begin;
        if (begin != null) {
            try {
                begin.invokeExact();
            } catch (Throwable ex) {
                // The agent never fails a request.
            }
        }
    }

    /** The request's scope closed on this thread: its fragment is flushed. */
    public static void end() {
        MethodHandle end = handles.end;
        if (end != null) {
            try {
                end.invokeExact();
            } catch (Throwable ex) {
                // The agent never fails a request.
            }
        }
    }

    /**
     * The code-paths node of the innermost instrumented call open on this thread, for a recorder to stamp the call it
     * records here, on the thread that issued it ({@code docs/PLAN-v2.md} §5.14, M5-4c): the fragment's sequence, the
     * node's index, and its method id packed into one long ({@code CodePaths.stamp()}); 0 when unknown, as without the
     * agent, which costs one volatile read. A call recorded on another thread than the one that issued it, as a
     * WebClient response or a streaming AI call, must not be stamped.
     */
    public static long stamp() {
        MethodHandle stamp = handles.stamp;
        if (stamp == null) {
            return 0L;
        }
        try {
            return (long) stamp.invokeExact();
        } catch (Throwable ex) {
            return 0L;
        }
    }

    /** The request on this thread entered {@code phase}. */
    public static void phase(RequestPhase phase) {
        MethodHandle mark = handles.phase;
        if (mark != null && phase != null) {
            try {
                mark.invokeExact(code(phase));
            } catch (Throwable ex) {
                // The agent never fails a request.
            }
        }
    }

    /**
     * The request on this thread is over, as when its response was written on a worker thread that no {@link #end()}
     * closes: nodes created from now on record no phase.
     */
    public static void clearPhase() {
        MethodHandle mark = handles.phase;
        if (mark != null) {
            try {
                mark.invokeExact(0);
            } catch (Throwable ex) {
                // The agent never fails a request.
            }
        }
    }

    /**
     * The request {@code requestId}'s handler only assembled its result, which runs later or elsewhere: a Spring MVC
     * handler that started async processing, or a Quarkus endpoint on the event loop, returning {@code Uni},
     * {@code Multi}, or {@code CompletionStage}, or whose resource method BootUI could not tell ({@code docs/PLAN-v2.md}
     * §5.14, M5-4b). Its tree times the assembly, not the work, and is kept out of {@code route-time-breakdown}'s handler
     * split. Spring WebFlux, where every handler only assembles, marks no request: its configuration tells Code Paths
     * once for the whole stack. Remembered for about the {@value #ASSEMBLY_ONLY_REQUESTS} most recent such requests,
     * only while the hooks reach a bridge; lock-free, so it never serializes requests.
     */
    public static void assemblyOnly(String requestId) {
        if (requestId == null || !bound()) {
            return;
        }
        if (ASSEMBLY_ONLY.putIfAbsent(requestId, Boolean.TRUE) != null) {
            return;
        }
        ASSEMBLY_ORDER.offer(requestId);
        if (ASSEMBLY_SIZE.incrementAndGet() > ASSEMBLY_ONLY_REQUESTS) {
            String eldest = ASSEMBLY_ORDER.poll();
            if (eldest != null) {
                ASSEMBLY_ONLY.remove(eldest);
                ASSEMBLY_SIZE.decrementAndGet();
            }
        }
    }

    /** Whether the request {@code requestId} was marked {@linkplain #assemblyOnly(String) assembly only}. */
    public static boolean isAssemblyOnly(String requestId) {
        return requestId != null && ASSEMBLY_ONLY.containsKey(requestId);
    }

    /** Tests only: forgets every request marked assembly only. */
    static void clearAssemblyOnly() {
        ASSEMBLY_ONLY.clear();
        ASSEMBLY_ORDER.clear();
        ASSEMBLY_SIZE.set(0);
    }

    /** Tests only: the requests remembered as assembly only. */
    static int assemblyOnlyCount() {
        return ASSEMBLY_ONLY.size();
    }

    /** The bridge's code for {@code phase}: {@code CodePaths.PHASE_FILTERS}, {@code PHASE_HANDLER}, or {@code PHASE_RESPONSE}. */
    static int code(RequestPhase phase) {
        return phase.ordinal() + 1;
    }

    /** Whether the hooks reach a bridge. */
    public static boolean bound() {
        return handles.begin != null;
    }

    /** Tests only: binds the hooks to {@code codePaths}, a {@code CodePaths} class, or unbinds them with {@code null}. */
    static void bind(Class<?> codePaths) {
        handles = codePaths == null ? new Handles(null, null, null, null) : Handles.bind(codePaths);
    }

    /** Tests only: binds the hooks to the bootstrap class path's bridge again. */
    static void rebind() {
        handles = Handles.locate();
    }

    private record Handles(MethodHandle begin, MethodHandle end, MethodHandle phase, MethodHandle stamp) {

        static Handles locate() {
            try {
                AgentBridgeAccess access = AgentBridgeAccess.locate();
                if (!access.codePathsSupported()) {
                    return new Handles(null, null, null, null);
                }
                return bind(Class.forName(AgentBridgeAccess.CODE_PATHS_CLASS, false, null));
            } catch (Throwable ex) {
                return new Handles(null, null, null, null);
            }
        }

        static Handles bind(Class<?> codePaths) {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Handles bound;
            try {
                bound = new Handles(
                        lookup.findStatic(codePaths, "begin", MethodType.methodType(void.class)),
                        lookup.findStatic(codePaths, "end", MethodType.methodType(void.class)),
                        lookup.findStatic(codePaths, "phase", MethodType.methodType(void.class, int.class)),
                        null);
            } catch (Throwable ex) {
                return new Handles(null, null, null, null);
            }
            try {
                // Optional: an agent predating M5-4c's stamps still times code paths, and every stamp is 0.
                return new Handles(
                        bound.begin,
                        bound.end,
                        bound.phase,
                        lookup.findStatic(codePaths, "stamp", MethodType.methodType(long.class)));
            } catch (Throwable ex) {
                return bound;
            }
        }
    }
}
