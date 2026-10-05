package io.github.jdubois.bootui.engine.javaagent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The adapters' and the engine's hooks into the BootUI agent's {@code thread-locals} sensor ({@code docs/PLAN-v2.md}
 * §5.16, M5-5f), which scans a thread's thread-local maps when a request's or a job's scope on a pooled thread closes,
 * against what they held when it opened. An adapter whose request scope ({@link AgentCodePaths#begin()}) is the
 * request's whole handling on a pooled worker says so ({@link #configure}: Spring MVC); one whose request runs
 * elsewhere opens a scope itself, on the thread that runs it, inside BootUI's context ({@link #open()}, {@link
 * #close(long)}): a Quarkus blocking resource method, a scheduled run, a Quarkus managed executor's task. The engine
 * names a reported thread local's holder on its drain thread ({@link #holder}) and tells the bridge which it excludes
 * ({@link #exclude}). Each call is one method handle bound once to the bridge on the bootstrap class path; without the
 * agent, or with one predating the sensor, every call does nothing and a token is 0. Never throws.
 */
public final class AgentThreadLocals {

    /** The bridge's thread-locals class. */
    static final String THREAD_LOCALS_CLASS = "io.github.jdubois.bootui.agent.bridge.ThreadLocals";

    private static volatile Handles handles = Handles.locate();

    private AgentThreadLocals() {}

    /** Whether an adapter's request scope is a thread-locals scope on this stack. */
    public static void configure(boolean adapterScopes) {
        MethodHandle configure = handles.configure;
        if (configure != null) {
            try {
                configure.invokeExact(adapterScopes);
            } catch (Throwable ex) {
                // The agent never fails the application.
            }
        }
    }

    /** Opens a scope on the calling thread, owned by BootUI's current context: a token for {@link #close}, else 0. */
    public static long open() {
        MethodHandle open = handles.open;
        if (open == null) {
            return 0L;
        }
        try {
            return (long) open.invokeExact();
        } catch (Throwable ex) {
            return 0L;
        }
    }

    /** Closes the scope {@link #open()} returned {@code token} for, on the same thread; 0 does nothing. */
    public static void close(long token) {
        MethodHandle close = handles.close;
        if (close != null && token != 0L) {
            try {
                close.invokeExact(token);
            } catch (Throwable ex) {
                // The agent never fails the application.
            }
        }
    }

    /**
     * The holder of the thread local the bridge registered as {@code id} with hash code {@code hash} in claim {@code
     * generation}: {@code {holder or null, initialValue, claimed, hint}}, or {@code null} when the agent ran out of
     * {@code budgetNanos} and should be asked again.
     */
    public static String[] holder(
            long generation, int id, int hash, String[] packages, String[] holders, long budgetNanos) {
        MethodHandle holder = handles.holder;
        if (holder == null) {
            return new String[] {null, "false", "false", null};
        }
        try {
            return (String[]) holder.invokeExact(generation, id, hash, packages, holders, budgetNanos);
        } catch (Throwable ex) {
            return new String[] {null, "false", "false", null};
        }
    }

    /** Tells the bridge to skip the thread local of {@code id} from now on: the engine excluded it. */
    public static void exclude(long generation, int id, int hash) {
        MethodHandle exclude = handles.exclude;
        if (exclude != null) {
            try {
                exclude.invokeExact(generation, id, hash);
            } catch (Throwable ex) {
                // It is reported again, and excluded again.
            }
        }
    }

    /** Whether the hooks reach a bridge carrying the sensor. */
    public static boolean bound() {
        return handles.open != null;
    }

    /** Tests only: binds the hooks to {@code threadLocals}, a {@code ThreadLocals} class, or unbinds them. */
    static void bind(Class<?> threadLocals) {
        handles = threadLocals == null ? Handles.NONE : Handles.bind(threadLocals);
    }

    /** Tests only: binds the hooks to the bootstrap class path's bridge again. */
    static void rebind() {
        handles = Handles.locate();
    }

    private record Handles(
            MethodHandle configure, MethodHandle open, MethodHandle close, MethodHandle holder, MethodHandle exclude) {

        static final Handles NONE = new Handles(null, null, null, null, null);

        static Handles locate() {
            try {
                if (!AgentBridgeAccess.locate().sideEffectsSupported()) {
                    return NONE;
                }
                return bind(Class.forName(THREAD_LOCALS_CLASS, false, null));
            } catch (Throwable ex) {
                return NONE;
            }
        }

        static Handles bind(Class<?> threadLocals) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Handles(
                        lookup.findStatic(threadLocals, "configure", MethodType.methodType(void.class, boolean.class)),
                        lookup.findStatic(threadLocals, "open", MethodType.methodType(long.class)),
                        lookup.findStatic(threadLocals, "close", MethodType.methodType(void.class, long.class)),
                        lookup.findStatic(
                                threadLocals,
                                "holder",
                                MethodType.methodType(
                                        String[].class,
                                        long.class,
                                        int.class,
                                        int.class,
                                        String[].class,
                                        String[].class,
                                        long.class)),
                        lookup.findStatic(
                                threadLocals,
                                "exclude",
                                MethodType.methodType(void.class, long.class, int.class, int.class)));
            } catch (Throwable ex) {
                // A bridge from before M5-5f.
                return NONE;
            }
        }
    }
}
