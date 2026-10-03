package io.github.jdubois.bootui.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The BootUI agent's {@code Premain-Class} and {@code Agent-Class} (PLAN-v2 D33). It appends this jar to the bootstrap
 * class path, so the bridge is defined once per JVM by the bootstrap class loader, then hands over to
 * {@link AgentBootstrap}, which it loads from the bootstrap class loader explicitly: after the append, the system class
 * loader would also resolve this jar's classes from the bootstrap path, so this class never references another class of
 * the jar directly. It writes to {@code System.err} only, never through a logging framework, and never throws: any
 * failure leaves the application running without the agent.
 */
public final class AgentLauncher {

    static final String BRIDGE = "io.github.jdubois.bootui.agent.bridge.AgentBridge";
    static final String BOOTSTRAP = "io.github.jdubois.bootui.agent.AgentBootstrap";

    private AgentLauncher() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        launch(arguments, instrumentation, true);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        launch(arguments, instrumentation, false);
    }

    static void launch(String arguments, Instrumentation instrumentation, boolean premain) {
        long started = System.nanoTime();
        try {
            Class<?> existing = bootstrapClass(BRIDGE);
            if (existing != null) {
                dormant(existing);
                return;
            }
            File jar = agentJar();
            if (jar == null) {
                log("unavailable: cannot locate the bootui-agent jar");
                return;
            }
            try (JarFile appended = new JarFile(jar)) {
                // The JVM keeps only the path: the handle can be closed once appended.
                instrumentation.appendToBootstrapClassLoaderSearch(appended);
            }
            Class<?> bridge = bootstrapClass(BRIDGE);
            if (bridge == null || bridge.getClassLoader() != null) {
                log("unavailable: the bridge did not load from the bootstrap class loader");
                return;
            }
            Class<?> bootstrap = Class.forName(BOOTSTRAP, true, null);
            Method start = bootstrap.getMethod(
                    "start", String.class, Instrumentation.class, String.class, boolean.class, long.class);
            start.invoke(
                    null, arguments, instrumentation, jar.getPath(), Boolean.valueOf(premain), Long.valueOf(started));
        } catch (Throwable ex) {
            Throwable cause = ex;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            log("unavailable: " + cause);
            report("the BootUI agent failed to start: " + cause);
        }
    }

    /** A bridge is already on the bootstrap path: another BootUI agent is attached, and this one stays dormant. */
    static void dormant(Class<?> bridge) {
        String present = "unknown";
        try {
            Object status = bridge.getMethod("status").invoke(null);
            Object agent = status instanceof Map ? ((Map<?, ?>) status).get("agent") : null;
            Object version = agent instanceof Map ? ((Map<?, ?>) agent).get("version") : null;
            if (version != null) {
                present = String.valueOf(version);
            }
        } catch (Throwable ignored) {
            // the reason below is enough
        }
        TreeSet<String> versions = new TreeSet<String>(javaAgentVersions());
        String message = versions.size() > 1
                ? "several BootUI agent versions on the command line " + versions + ": version " + present
                        + " is attached, the others stay dormant; keep only the bootui-agent jar of your BootUI version"
                : "another BootUI agent " + present + " is already attached; this -javaagent stays dormant";
        log(message);
        report(message);
    }

    /**
     * The versions of every {@code -javaagent} jar that is a BootUI agent, read from their manifests. Only on this dormant
     * path does the launcher touch the management API: the JVM's input arguments include {@code JAVA_TOOL_OPTIONS} and
     * argument files, and work on Windows, unlike the process command line.
     */
    static List<String> javaAgentVersions() {
        List<String> versions = new ArrayList<String>();
        try {
            List<String> arguments =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String argument : arguments) {
                if (!argument.startsWith("-javaagent:")) {
                    continue;
                }
                String path = argument.substring("-javaagent:".length());
                int options = path.indexOf('=');
                if (options >= 0) {
                    path = path.substring(0, options);
                }
                try (JarFile jar = new JarFile(path)) {
                    Manifest manifest = jar.getManifest();
                    if (manifest != null && manifest.getMainAttributes().getValue("BootUI-Agent-Protocol") != null) {
                        String version = manifest.getMainAttributes().getValue("Implementation-Version");
                        versions.add(version == null ? "unknown" : version);
                    }
                } catch (Exception ignored) {
                    // not a readable jar
                }
            }
        } catch (Throwable ignored) {
            // the command line is not available on this platform
        }
        return versions;
    }

    static Class<?> bootstrapClass(String name) {
        try {
            return Class.forName(name, false, null);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    static File agentJar() {
        try {
            return new File(AgentLauncher.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (Exception ex) {
            return null;
        }
    }

    static void report(String message) {
        try {
            Class<?> bridge = bootstrapClass(BRIDGE);
            if (bridge != null) {
                bridge.getMethod("message", String.class).invoke(null, message);
            }
        } catch (Throwable ignored) {
            // System.err already has it
        }
    }

    static void log(String message) {
        System.err.println("[BootUI agent] " + message);
    }
}
