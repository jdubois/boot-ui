package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.core.BootUiInfo;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * What the Java Agent panel needs to know about this application, read once when its adapter builds the service.
 *
 * @param bootUiVersion this BootUI's version, the agent version to attach
 * @param stack {@link AgentSetupSnippets#SPRING} or {@link AgentSetupSnippets#QUARKUS}
 * @param enabled whether this application claims the agent when it is attached ({@code bootui.agent.enabled})
 * @param disabledReason why agent support is off here, such as Quarkus production mode, or {@code null}
 * @param repository the local Maven repository, where the setup snippets expect the jar
 * @param workingDirectory the working directory, where the build tool is detected
 * @param nativeImage whether this is a native image, which cannot load a Java agent
 */
public record JavaAgentSettings(
        String bootUiVersion,
        String stack,
        boolean enabled,
        String disabledReason,
        Path repository,
        Path workingDirectory,
        boolean nativeImage) {

    /** The settings of this JVM: BootUI's version, {@code maven.repo.local} or {@code ~/.m2}, and {@code user.dir}. */
    public static JavaAgentSettings of(String stack, boolean enabled, String disabledReason) {
        return new JavaAgentSettings(
                BootUiInfo.VERSION,
                stack,
                enabled,
                disabledReason,
                AgentSetupSnippets.localRepository(
                        System.getProperty("maven.repo.local"), System.getProperty("user.home")),
                workingDirectory(System.getProperty("user.dir")),
                System.getProperty("org.graalvm.nativeimage.imagecode") != null);
    }

    private static Path workingDirectory(String directory) {
        if (directory == null) {
            return null;
        }
        try {
            return Path.of(directory);
        } catch (InvalidPathException ex) {
            return null;
        }
    }
}
