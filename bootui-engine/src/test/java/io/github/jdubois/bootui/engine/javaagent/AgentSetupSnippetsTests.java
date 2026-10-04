package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jdubois.bootui.core.dto.JavaAgentSnippetDto;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSetupSnippetsTests {

    private static final String JAR = "/repo/com/julien-dubois/bootui/bootui-agent/1.19.0/bootui-agent-1.19.0.jar";

    @TempDir
    Path directory;

    @Test
    void springSnippetsAttachTheJarInEveryBuildToolAndKeepJaCoCo() {
        Map<String, JavaAgentSnippetDto> snippets = byId(
                AgentSetupSnippets.snippets("1.19.0", JAR, true, AgentSetupSnippets.SPRING, AgentSetupSnippets.MAVEN));

        assertThat(snippets.keySet())
                .containsExactly(
                        "maven-plugin", "gradle-kotlin", "gradle-groovy", "surefire", "intellij", "java-tool-options");
        assertThat(snippets.get("maven-plugin").text())
                .contains("<artifactId>spring-boot-maven-plugin</artifactId>")
                .contains("<agent>" + JAR + "</agent>");
        assertThat(snippets.get("maven-plugin").language()).isEqualTo("xml");
        assertThat(snippets.get("gradle-kotlin").text())
                .contains("tasks.bootRun")
                .contains("jvmArgs(\"-javaagent:" + JAR);
        assertThat(snippets.get("gradle-groovy").text()).contains("bootRun {").contains("jvmArgs('-javaagent:" + JAR);
        assertThat(snippets.get("surefire").text()).contains("<argLine>@{argLine} -javaagent:" + JAR + "</argLine>");
        assertThat(snippets.get("intellij").text()).isEqualTo("-javaagent:" + JAR);
        assertThat(snippets.get("java-tool-options").text())
                .isEqualTo("JAVA_TOOL_OPTIONS=\"-javaagent:" + JAR + "\" ./mvnw spring-boot:run");
    }

    @Test
    void javaToolOptionsQuotesThePathInsideTheValueAndTheValueForTheShell() {
        assertThat(AgentSetupSnippets.javaToolOptions("/a b/agent.jar", "./mvnw spring-boot:run"))
                .isEqualTo("JAVA_TOOL_OPTIONS='\"-javaagent:/a b/agent.jar\"' ./mvnw spring-boot:run");
        assertThat(AgentSetupSnippets.jvmOption("-javaagent:/a\tb/agent.jar"))
                .isEqualTo("\"-javaagent:/a\tb/agent.jar\"");
        assertThat(AgentSetupSnippets.jvmOption("-javaagent:/it's/agent.jar"))
                .isEqualTo("\"-javaagent:/it's/agent.jar\"");
        assertThat(AgentSetupSnippets.jvmOption("-javaagent:/a\"b/agent.jar"))
                .isEqualTo("\"-javaagent:/a\"'\"'\"b/agent.jar\"");
        assertThat(AgentSetupSnippets.shellQuoted("\"-javaagent:/it's/agent.jar\""))
                .isEqualTo("'\"-javaagent:/it'\\''s/agent.jar\"'");
        assertThat(AgentSetupSnippets.javaToolOptions("C:\\Users\\me\\agent.jar", "./mvnw spring-boot:run"))
                .isEqualTo("JAVA_TOOL_OPTIONS=\"-javaagent:C:\\Users\\me\\agent.jar\" ./mvnw spring-boot:run");
    }

    @Test
    void theJavaToolOptionsSnippetStartsAJvmWithAnAgentWhosePathHasSpacesAndQuotes() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX shell");
        Path folder = Files.createDirectories(directory.resolve("my agents/it's a \"quoted\" dir"));
        Path jar = folder.resolve("probe agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", SnippetProbeAgent.class.getName());
        String entry = SnippetProbeAgent.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
                InputStream in = SnippetProbeAgent.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
        String java = ProcessHandle.current().info().command().orElseThrow();
        String run = AgentSetupSnippets.shellQuoted(java) + " -version";

        Process process = new ProcessBuilder("/bin/sh", "-c", AgentSetupSnippets.javaToolOptions(jar.toString(), run))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains(SnippetProbeAgent.MARKER);
    }

    @Test
    void theDetectedBuildToolComesFirst() {
        List<String> gradle = ids(
                AgentSetupSnippets.snippets("1.19.0", JAR, true, AgentSetupSnippets.SPRING, AgentSetupSnippets.GRADLE));

        assertThat(gradle).startsWith("gradle-kotlin", "gradle-groovy", "maven-plugin");
    }

    @Test
    void quarkusSnippetsUseDevModesJvmArguments() {
        Map<String, JavaAgentSnippetDto> maven = byId(
                AgentSetupSnippets.snippets("1.19.0", JAR, true, AgentSetupSnippets.QUARKUS, AgentSetupSnippets.MAVEN));
        Map<String, JavaAgentSnippetDto> gradle = byId(AgentSetupSnippets.snippets(
                "1.19.0", JAR, true, AgentSetupSnippets.QUARKUS, AgentSetupSnippets.GRADLE));

        assertThat(maven.keySet()).containsExactly("quarkus-dev", "surefire", "intellij", "java-tool-options");
        assertThat(maven.get("quarkus-dev").text())
                .isEqualTo("./mvnw quarkus:dev -Djvm.args=\"-javaagent:" + JAR + "\"");
        assertThat(gradle.get("quarkus-dev").text()).startsWith("./gradlew quarkusDev --jvm-args=");
    }

    @Test
    void aMissingJarAddsTheDownloadFirst() {
        List<JavaAgentSnippetDto> snippets = AgentSetupSnippets.snippets(
                "1.19.0", JAR, false, AgentSetupSnippets.SPRING, AgentSetupSnippets.UNKNOWN);

        assertThat(snippets.get(0).id()).isEqualTo("maven-download");
        assertThat(snippets.get(0).text())
                .isEqualTo("mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:1.19.0");
    }

    @Test
    void windowsPathsUseForwardSlashesInGradleStrings() {
        Map<String, JavaAgentSnippetDto> snippets = byId(AgentSetupSnippets.snippets(
                "1.19.0",
                "C:\\Users\\me\\bootui-agent.jar",
                true,
                AgentSetupSnippets.SPRING,
                AgentSetupSnippets.MAVEN));

        assertThat(snippets.get("gradle-kotlin").text()).contains("C:/Users/me/bootui-agent.jar");
        assertThat(snippets.get("intellij").text()).isEqualTo("-javaagent:C:\\Users\\me\\bootui-agent.jar");
    }

    @Test
    void theLocalRepositoryHonoursMavenRepoLocal() {
        assertThat(AgentSetupSnippets.localRepository(null, "/home/me")).isEqualTo(Path.of("/home/me/.m2/repository"));
        assertThat(AgentSetupSnippets.localRepository(" ", "/home/me")).isEqualTo(Path.of("/home/me/.m2/repository"));
        assertThat(AgentSetupSnippets.localRepository("/cache/m2", "/home/me")).isEqualTo(Path.of("/cache/m2"));
        assertThat(AgentSetupSnippets.repositoryJar(Path.of("/cache/m2"), "2.0.0"))
                .isEqualTo(Path.of("/cache/m2/com/julien-dubois/bootui/bootui-agent/2.0.0/bootui-agent-2.0.0.jar"));
        assertThat(AgentSetupSnippets.repositoryJar(null, "2.0.0")).isNull();
    }

    @Test
    void theBuildToolIsDetectedFromTheWorkingDirectory() throws Exception {
        assertThat(AgentSetupSnippets.detectBuildTool(directory)).isEqualTo(AgentSetupSnippets.UNKNOWN);
        Files.writeString(directory.resolve("build.gradle.kts"), "");
        assertThat(AgentSetupSnippets.detectBuildTool(directory)).isEqualTo(AgentSetupSnippets.GRADLE);
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        assertThat(AgentSetupSnippets.detectBuildTool(directory)).isEqualTo(AgentSetupSnippets.MAVEN);
        assertThat(AgentSetupSnippets.detectBuildTool(null)).isEqualTo(AgentSetupSnippets.UNKNOWN);
    }

    private static Map<String, JavaAgentSnippetDto> byId(List<JavaAgentSnippetDto> snippets) {
        return snippets.stream()
                .collect(Collectors.toMap(
                        JavaAgentSnippetDto::id, snippet -> snippet, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private static List<String> ids(List<JavaAgentSnippetDto> snippets) {
        return snippets.stream().map(JavaAgentSnippetDto::id).toList();
    }
}
