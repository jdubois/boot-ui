package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.core.dto.JavaAgentSetupDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSnippetDto;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The copy-paste lines that attach the BootUI Java agent ({@code docs/PLAN-v2.md} §5.13, D23): the Spring Boot Maven
 * plugin's {@code agents}, Gradle's {@code bootRun}, Quarkus dev mode's {@code -Djvm.args}, the Surefire and Failsafe
 * {@code argLine} appended to {@code @{argLine}} so JaCoCo keeps working, an IDE run configuration, and
 * {@code JAVA_TOOL_OPTIONS}. A pure function of its inputs: it reads nothing but whether files exist.
 */
public final class AgentSetupSnippets {

    public static final String MAVEN = "MAVEN";
    public static final String GRADLE = "GRADLE";
    public static final String UNKNOWN = "UNKNOWN";

    /** The stack whose snippets are wanted: Spring Boot (MVC or WebFlux). */
    public static final String SPRING = "spring";

    /** The stack whose snippets are wanted: Quarkus. */
    public static final String QUARKUS = "quarkus";

    static final String GROUP_PATH = "com/julien-dubois/bootui";
    static final String ARTIFACT_ID = "bootui-agent";
    static final String CENTRAL = "https://repo1.maven.org/maven2";
    static final String COORDINATES = "com.julien-dubois.bootui:bootui-agent";

    private AgentSetupSnippets() {}

    /**
     * The setup card: the jar to use (the attached agent's own, else the local Maven repository's copy for this BootUI
     * version), whether it exists, the build tool, and the snippets.
     *
     * @param version this BootUI's version, which is the agent version to attach
     * @param attachedJar the attached agent's jar, or {@code null} when none is attached
     * @param repository the local Maven repository
     * @param stack {@link #SPRING} or {@link #QUARKUS}
     * @param buildTool {@link #MAVEN}, {@link #GRADLE}, or {@link #UNKNOWN}
     */
    public static JavaAgentSetupDto setup(
            String version, String attachedJar, Path repository, String stack, String buildTool) {
        Path effectiveRepository =
                repository == null ? null : repository.toAbsolutePath().normalize();
        Path jar = attachedJar != null ? path(attachedJar) : null;
        if (jar == null) {
            jar = repositoryJar(effectiveRepository, version);
        }
        boolean found = jar != null && isFile(jar);
        String jarPath = jar == null ? repositoryJarPlaceholder(version) : jar.toString();
        return new JavaAgentSetupDto(
                jarPath, found, buildTool, snippets(version, jarPath, found, stack, buildTool, effectiveRepository));
    }

    /**
     * The snippets for one stack, the detected build tool's first. A missing jar adds a {@code curl} line and a
     * {@code dependency:get} line first, using Maven's default local repository when the repository is unknown.
     */
    public static List<JavaAgentSnippetDto> snippets(
            String version, String jarPath, boolean jarFound, String stack, String buildTool) {
        return snippets(version, jarPath, jarFound, stack, buildTool, null);
    }

    private static List<JavaAgentSnippetDto> snippets(
            String version, String jarPath, boolean jarFound, String stack, String buildTool, Path repository) {
        String option = jarPath.contains(" ") ? "\"-javaagent:" + jarPath + "\"" : "-javaagent:" + jarPath;
        String bare = "-javaagent:" + jarPath;
        String portable = "-javaagent:" + jarPath.replace('\\', '/');
        List<JavaAgentSnippetDto> snippets = new ArrayList<>();
        if (!jarFound) {
            snippets.add(new JavaAgentSnippetDto(
                    "curl-download", "Download the agent (curl)", "shell", curlDownload(version, jarPath)));
            snippets.add(new JavaAgentSnippetDto(
                    "maven-download",
                    "Download the agent",
                    "shell",
                    "mvn dependency:get -Dartifact=" + COORDINATES + ":" + shown(version)
                            + (repository == null ? "" : " -Dmaven.repo.local=" + shellQuoted(repository.toString()))));
        }
        if (!jarFound) {
            // Gradle has no one-line download: a detached configuration resolves the jar through the build's own
            // repositories and proxy settings, then copies it where BootUI looks for it.
            JavaAgentSnippetDto gradleKotlin = new JavaAgentSnippetDto(
                    "gradle-kotlin-download",
                    "Download the agent (Gradle, Kotlin DSL)",
                    "kotlin",
                    gradleDownload(version, jarPath, true));
            JavaAgentSnippetDto gradleGroovy = new JavaAgentSnippetDto(
                    "gradle-groovy-download",
                    "Download the agent (Gradle, Groovy DSL)",
                    "groovy",
                    gradleDownload(version, jarPath, false));
            int mavenAt = snippets.size() - 1;
            if (GRADLE.equals(buildTool)) {
                snippets.add(mavenAt, gradleGroovy);
                snippets.add(mavenAt, gradleKotlin);
            } else {
                snippets.add(gradleKotlin);
                snippets.add(gradleGroovy);
            }
        }
        if (QUARKUS.equals(stack)) {
            snippets.add(new JavaAgentSnippetDto(
                    "quarkus-dev",
                    "Quarkus dev mode",
                    "shell",
                    GRADLE.equals(buildTool)
                            ? "./gradlew quarkusDev --jvm-args=\"" + bare + "\""
                            : "./mvnw quarkus:dev -Djvm.args=\"" + bare + "\""));
        } else {
            JavaAgentSnippetDto maven =
                    new JavaAgentSnippetDto("maven-plugin", "Spring Boot Maven plugin", "xml", """
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <configuration>
                                    <agents>
                                        <agent>%s</agent>
                                    </agents>
                                </configuration>
                            </plugin>""".formatted(jarPath));
            JavaAgentSnippetDto kotlin =
                    new JavaAgentSnippetDto("gradle-kotlin", "Gradle (Kotlin DSL)", "kotlin", """
                    tasks.bootRun {
                        jvmArgs("%s")
                    }""".formatted(portable));
            JavaAgentSnippetDto groovy =
                    new JavaAgentSnippetDto("gradle-groovy", "Gradle (Groovy DSL)", "groovy", """
                    bootRun {
                        jvmArgs('%s')
                    }""".formatted(portable));
            if (GRADLE.equals(buildTool)) {
                snippets.add(kotlin);
                snippets.add(groovy);
                snippets.add(maven);
            } else {
                snippets.add(maven);
                snippets.add(kotlin);
                snippets.add(groovy);
            }
        }
        snippets.add(new JavaAgentSnippetDto("surefire", "Surefire and Failsafe", "xml", """
                <!-- In the maven-surefire-plugin and maven-failsafe-plugin configuration. @{argLine} keeps JaCoCo's
                     agent; without JaCoCo, declare an empty <argLine/> property. -->
                <configuration>
                    <argLine>@{argLine} %s</argLine>
                </configuration>""".formatted(option)));
        snippets.add(new JavaAgentSnippetDto("intellij", "IntelliJ IDEA", "text", option));
        // One command only: exported, it would attach the agent to every JVM of the shell (Maven, the Gradle daemon,
        // IDE tooling), each printing the class-data-sharing warning.
        String run = QUARKUS.equals(stack)
                ? (GRADLE.equals(buildTool) ? "./gradlew quarkusDev" : "./mvnw quarkus:dev")
                : (GRADLE.equals(buildTool) ? "./gradlew bootRun" : "./mvnw spring-boot:run");
        snippets.add(new JavaAgentSnippetDto(
                "java-tool-options", "JAVA_TOOL_OPTIONS", "shell", javaToolOptions(jarPath, run)));
        return List.copyOf(snippets);
    }

    /** A build-file task, run with {@code ./gradlew downloadBootuiAgent}, that copies the agent jar into its folder. */
    static String gradleDownload(String version, String jarPath, boolean kotlin) {
        String path = jarPath.replace('\\', '/');
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
        String q = kotlin ? "\"" : "'";
        String target;
        if (folder.startsWith("~/")) {
            String rest = quoteForGradle("/" + folder.substring(2), kotlin);
            target = "System.getProperty(" + q + "user.home" + q + ") + " + rest;
        } else {
            target = quoteForGradle(folder, kotlin);
        }
        String coordinates = COORDINATES + ":" + shown(version);
        return kotlin ? """
                // Needs mavenCentral() (or your corporate mirror) in repositories. Run: ./gradlew downloadBootuiAgent
                val bootuiAgent by configurations.creating { isTransitive = false }
                dependencies { bootuiAgent("%s") }
                tasks.register<Copy>("downloadBootuiAgent") {
                    from(bootuiAgent)
                    into(%s)
                }""".formatted(coordinates, target) : """
                // Needs mavenCentral() (or your corporate mirror) in repositories. Run: ./gradlew downloadBootuiAgent
                configurations { bootuiAgent { transitive = false } }
                dependencies { bootuiAgent '%s' }
                tasks.register('downloadBootuiAgent', Copy) {
                    from configurations.bootuiAgent
                    into %s
                }""".formatted(coordinates, target);
    }

    private static String quoteForGradle(String value, boolean kotlin) {
        return kotlin
                ? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
                : "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * A {@code curl} command that downloads the agent from Maven Central to {@code jarPath}, where BootUI looks for it.
     * It needs no Maven. A Windows path selects {@code curl.exe}, which PowerShell would otherwise shadow with its
     * {@code curl} alias for {@code Invoke-WebRequest}.
     */
    static String curlDownload(String version, String jarPath) {
        boolean windows = jarPath.length() > 2 && jarPath.charAt(1) == ':' && Character.isLetter(jarPath.charAt(0));
        String target = jarPath.startsWith("~/") ? "\"$HOME/" + jarPath.substring(2) + "\"" : shellQuoted(jarPath);
        String v = shown(version);
        return (windows ? "curl.exe" : "curl") + " -fL --create-dirs -o " + target + " " + CENTRAL + "/" + GROUP_PATH
                + "/" + ARTIFACT_ID + "/" + v + "/" + ARTIFACT_ID + "-" + v + ".jar";
    }

    /**
     * {@code run} with {@code JAVA_TOOL_OPTIONS} set to the agent option for {@code jarPath}. The JVM splits
     * {@code JAVA_TOOL_OPTIONS} on whitespace, so the option is quoted inside the value ({@link #jvmOption}), then the
     * value is quoted for a POSIX shell.
     */
    static String javaToolOptions(String jarPath, String run) {
        return "JAVA_TOOL_OPTIONS=" + shellQuoted(jvmOption("-javaagent:" + jarPath)) + " " + run;
    }

    /**
     * {@code option} as one token of {@code JAVA_TOOL_OPTIONS}: unchanged without whitespace or quotes, otherwise
     * double-quoted, with each double quote it contains as a single-quoted fragment. HotSpot joins adjacent quoted
     * fragments into one option.
     */
    static String jvmOption(String option) {
        boolean plain = option.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'');
        if (plain) {
            return option;
        }
        StringBuilder quoted = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < option.length(); i++) {
            char c = option.charAt(i);
            if (c == '"') {
                if (!run.isEmpty()) {
                    quoted.append('"').append(run).append('"');
                    run.setLength(0);
                }
                quoted.append("'\"'");
            } else {
                run.append(c);
            }
        }
        if (!run.isEmpty()) {
            quoted.append('"').append(run).append('"');
        }
        return quoted.toString();
    }

    /** {@code value} as one POSIX shell word: double-quoted when it holds only safe characters, else single-quoted. */
    static String shellQuoted(String value) {
        boolean safe = value.chars().allMatch(c -> Character.isLetterOrDigit(c) || "-_./:@%+=,~\\".indexOf(c) >= 0);
        return safe ? "\"" + value + "\"" : "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * The local Maven repository: the {@code maven.repo.local} system property when set, else
     * {@code ~/.m2/repository}.
     */
    public static Path localRepository(String mavenRepoLocal, String userHome) {
        if (mavenRepoLocal != null && !mavenRepoLocal.isBlank()) {
            Path configured = path(mavenRepoLocal.trim());
            if (configured != null) {
                return configured.toAbsolutePath().normalize();
            }
        }
        Path home = userHome == null ? null : path(userHome);
        return home == null ? null : home.resolve(".m2").resolve("repository");
    }

    /** The agent jar for {@code version} in a local Maven repository. */
    public static Path repositoryJar(Path repository, String version) {
        if (repository == null || !known(version)) {
            return null;
        }
        return repository
                .resolve(GROUP_PATH)
                .resolve(ARTIFACT_ID)
                .resolve(version)
                .resolve(ARTIFACT_ID + "-" + version + ".jar");
    }

    /** The build tool of a working directory: Maven when a {@code pom.xml} is there, else Gradle, else unknown. */
    public static String detectBuildTool(Path directory) {
        if (directory == null) {
            return UNKNOWN;
        }
        if (isFile(directory.resolve("pom.xml"))) {
            return MAVEN;
        }
        if (isFile(directory.resolve("build.gradle")) || isFile(directory.resolve("build.gradle.kts"))) {
            return GRADLE;
        }
        return UNKNOWN;
    }

    /** The version to print: a placeholder when BootUI does not know its own (a build from source). */
    private static String shown(String version) {
        return known(version) ? version : "<version>";
    }

    private static boolean known(String version) {
        return version != null && !version.isBlank() && !"unknown".equals(version);
    }

    private static String repositoryJarPlaceholder(String version) {
        String v = shown(version);
        return "~/.m2/repository/" + GROUP_PATH + "/" + ARTIFACT_ID + "/" + v + "/" + ARTIFACT_ID + "-" + v + ".jar";
    }

    private static boolean isFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (SecurityException ex) {
            return false;
        }
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            return null;
        }
    }
}
