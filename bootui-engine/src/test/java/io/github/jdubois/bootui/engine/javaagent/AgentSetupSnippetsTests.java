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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @CsvSource(
            value = {
                "spring,MAVEN",
                "spring,GRADLE",
                "spring,UNKNOWN",
                "spring,null",
                "quarkus,MAVEN",
                "quarkus,GRADLE",
                "quarkus,UNKNOWN",
                "quarkus,null"
            },
            nullValues = "null")
    void aMissingRepositoryJarIsDownloadedToTheRepositoryUsedByTheAttachment(String stack, String buildTool) {
        Path repository = directory.resolve("custom-repository");
        Path jar = AgentSetupSnippets.repositoryJar(repository, "1.19.0");

        var setup = AgentSetupSnippets.setup("1.19.0", null, repository, stack, buildTool);
        Map<String, JavaAgentSnippetDto> snippets = byId(setup.snippets());

        assertThat(setup.jarFound()).isFalse();
        assertThat(setup.jarPath()).isEqualTo(jar.toString());
        assertThat(setup.buildTool()).isEqualTo(buildTool);
        assertThat(setup.snippets().get(0).id()).isEqualTo("maven-download");
        assertThat(snippets.get("maven-download").text())
                .isEqualTo("mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:1.19.0"
                        + " -Dmaven.repo.local=" + AgentSetupSnippets.shellQuoted(repository.toString()));
        assertThat(snippets.get("intellij").text()).isEqualTo("-javaagent:" + jar);
        assertThat(snippets.get("java-tool-options").text())
                .contains(AgentSetupSnippets.shellQuoted(AgentSetupSnippets.jvmOption("-javaagent:" + jar)));
        assertThat(snippets.get("quarkus".equals(stack) ? "quarkus-dev" : "maven-plugin")
                        .text())
                .contains(jar.toString());
        assertThat(Files.exists(repository)).isFalse();
    }

    @Test
    void theDefaultRepositoryIsExplicitInTheDownloadToo() {
        Path repository = AgentSetupSnippets.localRepository(null, directory.toString());

        var setup = AgentSetupSnippets.setup(
                "1.19.0", null, repository, AgentSetupSnippets.SPRING, AgentSetupSnippets.MAVEN);

        assertThat(setup.jarPath())
                .isEqualTo(
                        AgentSetupSnippets.repositoryJar(repository, "1.19.0").toString());
        assertThat(setup.snippets().get(0).text())
                .endsWith(" -Dmaven.repo.local=" + AgentSetupSnippets.shellQuoted(repository.toString()));
    }

    @Test
    void aRelativeRepositoryUsesTheSameNormalizedAbsolutePathForDownloadAndAttachment() {
        Path relative = Path.of("relative cache", "unused", "..", "repository");
        Path repository = relative.toAbsolutePath().normalize();
        assertThat(AgentSetupSnippets.localRepository(relative.toString(), directory.toString()))
                .isEqualTo(repository);

        var setup = AgentSetupSnippets.setup(
                "1.19.0", null, relative, AgentSetupSnippets.SPRING, AgentSetupSnippets.UNKNOWN);

        assertThat(setup.jarPath())
                .isEqualTo(
                        AgentSetupSnippets.repositoryJar(repository, "1.19.0").toString());
        assertThat(setup.snippets().get(0).text())
                .endsWith(" -Dmaven.repo.local=" + AgentSetupSnippets.shellQuoted(repository.toString()));
        assertThat(byId(setup.snippets()).get("intellij").text()).isEqualTo("\"-javaagent:" + setup.jarPath() + "\"");
    }

    @Test
    void theDownloadKeepsWhitespaceQuotesAndShellMetacharactersInOneLiteralArgument() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX shell");
        Path repository = directory.resolve("my cache\t/it's a \"quoted\" $HOME `pwd`; & | > * # repo");
        var setup = AgentSetupSnippets.setup(
                "1.19.0", null, repository, AgentSetupSnippets.QUARKUS, AgentSetupSnippets.GRADLE);
        String download = setup.snippets().get(0).text();

        Process process = new ProcessBuilder("/bin/sh", "-c", "mvn() { printf '%s\\n' \"$@\"; }; " + download)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output)
                .isEqualTo("dependency:get\n-Dartifact=com.julien-dubois.bootui:bootui-agent:1.19.0\n"
                        + "-Dmaven.repo.local=" + repository + "\n");
        assertThat(setup.jarPath())
                .isEqualTo(
                        AgentSetupSnippets.repositoryJar(repository, "1.19.0").toString());
        assertThat(byId(setup.snippets()).get("java-tool-options").text())
                .isEqualTo(AgentSetupSnippets.javaToolOptions(setup.jarPath(), "./gradlew quarkusDev"));
        assertThat(Files.exists(repository)).isFalse();
    }

    @Test
    void anUnknownRepositoryKeepsTheDefaultDownloadAndPlaceholder() {
        var setup =
                AgentSetupSnippets.setup("1.19.0", null, null, AgentSetupSnippets.SPRING, AgentSetupSnippets.UNKNOWN);

        assertThat(setup.jarFound()).isFalse();
        assertThat(setup.jarPath())
                .isEqualTo("~/.m2/repository/com/julien-dubois/bootui/bootui-agent/1.19.0/bootui-agent-1.19.0.jar");
        assertThat(setup.snippets().get(0).text())
                .isEqualTo("mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:1.19.0");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPresentRepositoryOrAttachedJarNeedsNoDownload(boolean attached) throws Exception {
        Path repository = directory.resolve("repository");
        Path jar = attached
                ? directory.resolve("attached-agent.jar")
                : AgentSetupSnippets.repositoryJar(repository, "1.19.0");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "");

        var setup = AgentSetupSnippets.setup(
                "1.19.0",
                attached ? jar.toString() : null,
                repository,
                AgentSetupSnippets.SPRING,
                AgentSetupSnippets.MAVEN);

        assertThat(setup.jarFound()).isTrue();
        assertThat(setup.jarPath()).isEqualTo(jar.toString());
        assertThat(ids(setup.snippets())).doesNotContain("maven-download");
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
