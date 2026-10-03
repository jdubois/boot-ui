package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentJarsTests {

    @TempDir
    Path directory;

    @Test
    void recognizesTheAgentJarByItsManifestAmongTheJavaagentArguments() throws Exception {
        Path agent = jar("renamed.jar", true);
        Path other = jar("other-agent.jar", false);

        assertThat(AgentJars.isAgentJar(agent)).isTrue();
        assertThat(AgentJars.isAgentJar(other)).isFalse();
        assertThat(AgentJars.isAgentJar(directory.resolve("missing.jar"))).isFalse();
        assertThat(AgentJars.fromJvmArguments(List.of(
                        "-Xmx1g",
                        "-javaagent:" + agent + "=debug",
                        "-javaagent:" + other,
                        "-javaagent:" + directory.resolve("missing.jar"))))
                .containsExactly(agent.toAbsolutePath().normalize());
        assertThat(AgentJars.fromJvmArguments(null)).isEmpty();
    }

    private Path jar(String name, boolean bootUiAgent) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (bootUiAgent) {
            manifest.getMainAttributes().putValue(AgentJars.PROTOCOL_ATTRIBUTE, "1");
        }
        Path jar = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // A manifest is all the jar needs.
        }
        return jar;
    }
}
