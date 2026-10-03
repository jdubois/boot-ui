package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.JavaAgentSnippetDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
