package io.github.jdubois.bootui.engine.javaagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Recognizes the BootUI agent jar, which a {@code -javaagent} flag also appends to the system class path: a dependency
 * inventory labels it the BootUI agent instead of counting it as an application library.
 */
public final class AgentJars {

    /** The manifest attribute every BootUI agent jar carries, with its bridge protocol. */
    public static final String PROTOCOL_ATTRIBUTE = "BootUI-Agent-Protocol";

    /** How a dependency inventory labels the agent jar. */
    public static final String LABEL = "BootUI agent";

    private static final String JAVAAGENT = "-javaagent:";

    private AgentJars() {}

    /**
     * The BootUI agent jars among the JVM's {@code -javaagent} arguments (which include {@code JAVA_TOOL_OPTIONS}), as
     * absolute normalized paths. Only those jars' manifests are read, so a class path of hundreds of jars costs nothing.
     */
    public static Set<Path> fromJvmArguments(List<String> arguments) {
        Set<Path> jars = new LinkedHashSet<>();
        if (arguments == null) {
            return jars;
        }
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith(JAVAAGENT)) {
                continue;
            }
            String path = argument.substring(JAVAAGENT.length());
            int options = path.indexOf('=');
            if (options >= 0) {
                path = path.substring(0, options);
            }
            try {
                Path jar = Path.of(path).toAbsolutePath().normalize();
                if (isAgentJar(jar)) {
                    jars.add(jar);
                }
            } catch (InvalidPathException | SecurityException ex) {
                // Not a readable path: not a jar this inventory can see either.
            }
        }
        return jars;
    }

    /** Whether a jar's manifest carries {@value #PROTOCOL_ATTRIBUTE}; false for anything unreadable. */
    public static boolean isAgentJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            Manifest manifest = file.getManifest();
            return manifest != null && manifest.getMainAttributes().getValue(PROTOCOL_ATTRIBUTE) != null;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
