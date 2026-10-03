package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/** The launcher, the layout, and dormancy, in forked JVMs against the packaged jar (PLAN-v2 D33, M5-1). */
class AgentLauncherIT {

    @Test
    void theJarHasD33sLayout() throws Exception {
        try (JarFile jar = new JarFile(ChildJvm.AGENT.toFile())) {
            List<String> rootClasses = jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .toList();
            List<String> launcher = List.of(
                    "io/github/jdubois/bootui/agent/AgentLauncher.class",
                    "io/github/jdubois/bootui/agent/AgentBootstrap.class",
                    "io/github/jdubois/bootui/agent/AgentClassLoader.class");
            assertThat(rootClasses)
                    .as("only the launcher, its class loader, and the bridge sit at the root")
                    .allMatch(name ->
                            name.startsWith("io/github/jdubois/bootui/agent/bridge/") || launcher.contains(name))
                    .containsAll(launcher)
                    .contains(
                            "io/github/jdubois/bootui/agent/bridge/AgentBridge.class",
                            "io/github/jdubois/bootui/agent/bridge/TaskPropagation.class");
            assertThat(jar.getEntry("inst/io/github/jdubois/bootui/agent/BootUiAgent.classdata"))
                    .isNotNull();
            assertThat(jar.stream().map(JarEntry::getName))
                    .anyMatch(name -> name.startsWith("inst/io/github/jdubois/bootui/agent/shaded/bytebuddy/"))
                    .noneMatch(name -> name.startsWith("net/bytebuddy/") || name.startsWith("inst/net/bytebuddy/"))
                    .noneMatch(name -> name.contains("ithook"))
                    .noneMatch(name -> name.contains("META-INF/services/"));
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue("Premain-Class")).isEqualTo("io.github.jdubois.bootui.agent.AgentLauncher");
            assertThat(main.getValue("Agent-Class")).isEqualTo("io.github.jdubois.bootui.agent.AgentLauncher");
            assertThat(main.getValue("Can-Retransform-Classes")).isEqualTo("true");
            assertThat(main.getValue("BootUI-Agent-Protocol")).isEqualTo("1");
            assertThat(main.getValue("Implementation-Version")).isNotBlank();
        }
    }

    @Test
    void aDormantAgentLoadsNoByteBuddyAndInstallsNothing() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-Xlog:class+load=info"), "status");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("BRIDGE")).isEqualTo("bootstrap");
        assertThat(output.text()).contains("[BootUI agent] BootUI agent").contains("dormant until BootUI claims it");
        assertThat(output.text()).doesNotContain("shaded.bytebuddy");
        assertThat(output.value("STATUS"))
                .contains("attached=true")
                .contains("claim=null")
                .contains("installer=null");
        assertThat(output.value("LOADERS")).isEqualTo("bootstrap,bootstrap");
    }

    @Test
    void theLauncherWorksWithoutBytecodeVerification() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:-BytecodeVerificationRemote",
                        ChildJvm.javaAgent(ChildJvm.AGENT)),
                "status");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("STATUS")).as(output.toString()).contains("attached=true");
        assertThat(output.value("LOADERS")).isEqualTo("bootstrap,bootstrap");
    }

    @Test
    void aSecondCopyStaysDormant() throws Exception {
        Path copy = ChildJvm.WORK.resolve("bootui-agent-copy.jar");
        Files.createDirectories(ChildJvm.WORK);
        Files.copy(ChildJvm.AGENT, copy, StandardCopyOption.REPLACE_EXISTING);

        ChildJvm.Output twice =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), ChildJvm.javaAgent(ChildJvm.AGENT)), "status");
        ChildJvm.Output copies =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), ChildJvm.javaAgent(copy)), "status");

        for (ChildJvm.Output output : List.of(twice, copies)) {
            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.text()).contains("is already attached; this -javaagent stays dormant");
            assertThat(output.value("STATUS")).contains("attached=true");
        }
    }

    @Test
    void aMismatchedSecondVersionIsNamed() throws Exception {
        Path other = ChildJvm.WORK.resolve("bootui-agent-0.0.1.jar");
        Files.createDirectories(ChildJvm.WORK);
        withVersion(ChildJvm.AGENT, other, "0.0.1");

        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), ChildJvm.javaAgent(other)), "status");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text())
                .contains("several BootUI agent versions on the command line")
                .contains("0.0.1");
    }

    @Test
    void theJarOnTheClassPathWithoutJavaagentIsNotAttached() throws Exception {
        ChildJvm.Output output = ChildJvm.run(List.of(), ChildJvm.AGENT, "status");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("BRIDGE")).isEqualTo("none");
        assertThat(output.text()).doesNotContain("[BootUI agent]");
    }

    /** Copies the agent jar with another Implementation-Version, as an older BootUI's agent would carry. */
    static void withVersion(Path source, Path target, String version) throws Exception {
        try (JarFile jar = new JarFile(source.toFile())) {
            Manifest manifest = new Manifest(jar.getManifest());
            manifest.getMainAttributes().putValue("Implementation-Version", version);
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                for (JarEntry entry : jar.stream().toList()) {
                    if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(entry.getName()));
                    try (var in = jar.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
    }
}
