package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The adapters' hooks into the BootUI agent's {@code code-paths} sensor ({@code docs/PLAN-v2.md} §5.14, M5-4a): where an
 * adapter opens and closes a request's scope on the handling thread ({@link #begin()}, {@link #end()}), so the sensor's
 * fragment covers the request apart from application filters outside it, and where it marks the request's phase
 * ({@link #phase}) and where the request is over on a thread no {@link #end()} closes ({@link #clearPhase()}), so each
 * method node records the phase it entered in. Each call is one method handle bound once to
 * the bridge on the bootstrap class path; without the agent, or with one predating the sensor, every call does nothing.
 * Never throws.
 */
public final class AgentCodePaths {

    private static volatile Handles handles = Handles.locate();

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
        handles = codePaths == null ? new Handles(null, null, null) : Handles.bind(codePaths);
    }

    /** Tests only: binds the hooks to the bootstrap class path's bridge again. */
    static void rebind() {
        handles = Handles.locate();
    }

    private record Handles(MethodHandle begin, MethodHandle end, MethodHandle phase) {

        static Handles locate() {
            try {
                AgentBridgeAccess access = AgentBridgeAccess.locate();
                if (!access.codePathsSupported()) {
                    return new Handles(null, null, null);
                }
                return bind(Class.forName(AgentBridgeAccess.CODE_PATHS_CLASS, false, null));
            } catch (Throwable ex) {
                return new Handles(null, null, null);
            }
        }

        static Handles bind(Class<?> codePaths) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Handles(
                        lookup.findStatic(codePaths, "begin", MethodType.methodType(void.class)),
                        lookup.findStatic(codePaths, "end", MethodType.methodType(void.class)),
                        lookup.findStatic(codePaths, "phase", MethodType.methodType(void.class, int.class)));
            } catch (Throwable ex) {
                return new Handles(null, null, null);
            }
        }
    }
}
