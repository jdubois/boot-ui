package io.github.jdubois.bootui.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Defined by the bootstrap class loader from the appended agent jar: creates the isolated {@link AgentClassLoader} and
 * starts the agent's implementation in it. Public, so the launcher can call it across class loaders.
 */
public final class AgentBootstrap {

    static final String AGENT = "io.github.jdubois.bootui.agent.BootUiAgent";

    private AgentBootstrap() {}

    public static void start(
            String arguments, Instrumentation instrumentation, String jarPath, boolean premain, long startedNanos)
            throws Exception {
        JarFile jar = new JarFile(jarPath);
        Manifest manifest = jar.getManifest();
        String version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        AgentClassLoader loader = new AgentClassLoader(jar, jarPath, ClassLoader.getPlatformClassLoader());
        Class<?> agent = Class.forName(AGENT, true, loader);
        Method start = agent.getMethod(
                "start", String.class, Instrumentation.class, String.class, String.class, boolean.class, long.class);
        start.invoke(
                null,
                arguments,
                instrumentation,
                version == null ? "unknown" : version,
                jarPath,
                Boolean.valueOf(premain),
                Long.valueOf(startedNanos));
    }
}
