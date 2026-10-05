package io.github.jdubois.bootui.engine.javaagent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The adapters' hook into the BootUI agent's {@code blocking} sensor ({@code docs/PLAN-v2.md} §5.16, M5-5c): an adapter
 * calls {@link #register()} on a thread it classifies as an event loop, as Reactor Netty's on Spring WebFlux and for a
 * WebClient, or Vert.x's on Quarkus, where it runs a request or a client response, so the sensor reports blocking calls
 * started on it. The bridge decides nothing about threads itself. One method handle bound once to the bridge on the
 * bootstrap class path; without the agent, or with one predating the sensor, a call does nothing. The bridge returns at
 * once for a loop it already knows, and does nothing unless the claim asks for the sensor. Never throws.
 */
public final class AgentEventLoops {

    /** The blocking sensor's bridge class, beside the bridge. */
    static final String BLOCKING_CLASS = "io.github.jdubois.bootui.agent.bridge.Blocking";

    private static volatile MethodHandle register = locate();

    private AgentEventLoops() {}

    /** Registers the calling thread, which the adapter classifies as an event loop. */
    public static void register() {
        MethodHandle handle = register;
        if (handle != null) {
            try {
                handle.invokeExact();
            } catch (Throwable ex) {
                // The agent never fails a request.
            }
        }
    }

    /** Whether the hook reaches a bridge carrying the blocking sensor: adapters skip classifying a thread otherwise. */
    public static boolean bound() {
        return register != null;
    }

    private static MethodHandle locate() {
        try {
            if (!AgentBridgeAccess.locate().sideEffectsSupported()) {
                return null;
            }
            return bind(Class.forName(BLOCKING_CLASS, false, null));
        } catch (Throwable ex) {
            return null;
        }
    }

    private static MethodHandle bind(Class<?> blocking) {
        try {
            return MethodHandles.publicLookup()
                    .findStatic(blocking, "registerEventLoop", MethodType.methodType(void.class));
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Tests only: binds the hook to {@code blocking}, a {@code Blocking} class, or unbinds it with {@code null}. */
    static void bindTo(Class<?> blocking) {
        register = blocking == null ? null : bind(blocking);
    }

    /** Tests only: binds the hook to the bootstrap class path's bridge again. */
    static void rebind() {
        register = locate();
    }
}
