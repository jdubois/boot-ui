package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.lang.instrument.Instrumentation;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * The agent's implementation entry point, defined by the isolated {@link AgentClassLoader}. It keeps
 * {@code Instrumentation} to itself, installs its handler into the bridge, and stays dormant: no transformer and no Byte
 * Buddy class until an application claims it with a sensor to install. This class must not reference Byte Buddy, so a
 * dormant agent never loads it.
 */
public final class BootUiAgent {

    private BootUiAgent() {}

    public static void start(
            String arguments,
            Instrumentation instrumentation,
            String version,
            String jarPath,
            boolean premain,
            long startedNanos) {
        AgentTestHook hook = hook();
        String loadMode = premain ? "javaagent" : "attach";
        long startupMicros = (System.nanoTime() - startedNanos) / 1_000L;
        AgentHandler handler = new AgentHandler(instrumentation, hook, version, loadMode, jarPath, startupMicros);
        if (!AgentBridge.install(handler)) {
            String message = "another BootUI agent is already attached; this one stays dormant";
            AgentBridge.message(message);
            System.err.println("[BootUI agent] " + message);
            return;
        }
        String attached = "BootUI agent " + version + " attached (" + loadMode + ")";
        // The status keeps this message after BootUI claims and arms the agent, so it states only the event; the
        // console line, read at startup, also says what the agent does until then.
        AgentBridge.message(attached);
        System.err.println("[BootUI agent] " + attached + "; dormant until BootUI claims it");
    }

    /** The test hook from the test variant of the jar; the published jar has none. */
    static AgentTestHook hook() {
        Iterator<AgentTestHook> hooks = ServiceLoader.load(AgentTestHook.class, BootUiAgent.class.getClassLoader())
                .iterator();
        return hooks.hasNext() ? hooks.next() : AgentTestHook.NONE;
    }
}
