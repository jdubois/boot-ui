package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CliDistributionIT {
    @TempDir
    Path directory;

    @Test
    void releasedDocsAndHelpNeverContactTheRepositoryEvenOnATerminal() throws Exception {
        Path jar = directory.resolve("bootui.jar");
        Files.write(jar, versionedJar("1.0.0"));
        try (var repository = new UpdateTestRepository()) {
            repository.metadata("2.0.0");
            Map<String, String> environment =
                    Map.of("BOOTUI_MAVEN_REPO", repository.url(), "BOOTUI_INSTALL_DIR", directory.toString());
            String classpath =
                    jar + File.pathSeparator + Path.of("target/test-classes").toAbsolutePath();
            for (List<String> args : List.of(
                    List.of("docs", "cli"),
                    List.of("docs", "--search", "blocking sensor", "--json"),
                    List.of("--help"),
                    List.of("--version"),
                    List.of("docs", "--help"),
                    List.of("update", "--help"))) {
                List<String> command =
                        new ArrayList<>(List.of(java(), "-cp", classpath, CliTerminalProcessMain.class.getName()));
                command.addAll(args);
                assertThat(process(command, environment).code()).isZero();
            }
            assertThat(repository.requests).isEmpty();
            assertThat(directory.resolve("update-checked-at")).doesNotExist();
            Result page = process(List.of(java(), "-jar", jar.toString(), "docs", "cli", "--json"), environment);
            assertThat(JsonValue.parse(page.output()).get("content").asString(""))
                    .startsWith("# Command line");
            assertThat(JsonValue.parse(page.output()).get("version").asString(""))
                    .isEqualTo("1.0.0");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void realShellInstallUpdatesInPlaceAndUninstallsItsProvenance() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            repository.metadata("1.0.0");
            repository.artifact("1.0.0", versionedJar("1.0.0"), "SHA-512");
            repository.artifact("2.0.0", versionedJar("2.0.0"), "SHA-512");
            Path install = directory.resolve("custom installation");
            Path bin = directory.resolve("custom bin");
            Map<String, String> env = Map.of(
                    "BOOTUI_MAVEN_REPO",
                    repository.url(),
                    "BOOTUI_INSTALL_DIR",
                    install.toString(),
                    "BOOTUI_BIN_DIR",
                    bin.toString(),
                    "BOOTUI_NO_UPDATE_CHECK",
                    "1",
                    "JAVA_HOME",
                    System.getProperty("java.home"));
            Path script = Path.of("../docs/.vuepress/public/install.sh").toAbsolutePath();
            assertThat(process(List.of("sh", script.toString()), env).code()).isZero();
            Path launcher = bin.resolve("bootui");
            assertThat(Files.readString(install.resolve(Installation.RECORD)))
                    .contains(install.toString(), bin.toString(), repository.url());
            assertThat(process(List.of(launcher.toString(), "--version"), env).output())
                    .contains("bootui 1.0.0");
            repository.metadata("2.0.0");
            Result check = process(List.of(launcher.toString(), "update", "--check", "--json"), env);
            assertThat(check.code()).isEqualTo(3);
            assertThat(JsonValue.parse(check.output()).get("status").asString(""))
                    .isEqualTo("available");
            Result update = process(List.of(launcher.toString(), "update", "--json"), env);
            assertThat(update.code()).as(update.output()).isZero();
            assertThat(JsonValue.parse(update.output()).get("status").asString(""))
                    .isEqualTo("updated");
            assertThat(process(List.of(launcher.toString(), "--version"), env).output())
                    .contains("bootui 2.0.0");
            assertThat(process(List.of(launcher.toString(), "docs", "properties"), env)
                            .output())
                    .startsWith("# ");
            assertThat(process(List.of("sh", script.toString(), "--uninstall"), env)
                            .code())
                    .isZero();
            assertThat(launcher).doesNotExist();
            assertThat(install.resolve(Installation.RECORD)).doesNotExist();
            assertThat(install.resolve("update.lock")).doesNotExist();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLockedOrChangedTargetDoesNotLoseItsWorkingVersion() throws Exception {
        Path jar = directory.resolve("bootui.jar");
        Files.write(jar, versionedJar("1.0.0"));
        Path launcher = directory.resolve("bootui");
        Files.writeString(launcher, "# Generated by the BootUI CLI installer.\nJAR=\"" + jar + "\"\n");
        Files.writeString(
                directory.resolve(Installation.RECORD),
                "bootui-install-v1\n" + jar + "\n" + launcher + "\n" + ReleaseRepository.DEFAULT + "\n");
        try (var repository = new UpdateTestRepository();
                var channel = java.nio.channels.FileChannel.open(
                        directory.resolve("update.lock"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThat(lock.isValid()).isTrue();
            repository.metadata("2.0.0");
            Result update = process(
                    List.of(java(), "-jar", jar.toString(), "update", "--json"),
                    Map.of("BOOTUI_MAVEN_REPO", repository.url()));
            assertThat(update.code()).isEqualTo(1);
            assertThat(JsonValue.parse(update.output()).get("message").asString(""))
                    .contains("Another BootUI update");
            assertThat(process(List.of(java(), "-jar", jar.toString(), "--version"), Map.of())
                            .output())
                    .contains("1.0.0");
        }
    }

    @Test
    void documentationOutputRemainsUtf8WithAnAsciiJvmDefault() throws Exception {
        Path jar = directory.resolve("bootui.jar");
        Files.write(jar, versionedJar("1.0.0"));
        String content;
        try (JarFile original = new JarFile(jar.toFile());
                var input = original.getInputStream(original.getJarEntry("bootui-docs/CLI.md"))) {
            content = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Result result =
                process(List.of(java(), "-Dfile.encoding=US-ASCII", "-jar", jar.toString(), "docs", "cli"), Map.of());
        assertThat(result.code()).isZero();
        assertThat(result.output()).isEqualTo(content);
        Result json = process(
                List.of(java(), "-Dfile.encoding=US-ASCII", "-jar", jar.toString(), "docs", "cli", "--json"), Map.of());
        assertThat(JsonValue.parse(json.output()).get("content").asString("")).isEqualTo(content);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.WINDOWS)
    void windowsManagedUpdateDoesNotAttemptToReplaceItsRunningJar() throws Exception {
        var target = UpdateTestRepository.install(directory, ReleaseRepository.DEFAULT, true, true);
        Files.write(target.jar(), versionedJar("1.0.0"));
        try (var repository = new UpdateTestRepository()) {
            repository.metadata("2.0.0");
            Result result = process(
                    List.of(java(), "-jar", target.jar().toString(), "update", "--json"),
                    Map.of("BOOTUI_MAVEN_REPO", repository.url()));
            assertThat(result.code()).isZero();
            assertThat(JsonValue.parse(result.output()).get("status").asString(""))
                    .isEqualTo("manual-action-required");
            assertThat(repository.requests).singleElement().asString().endsWith("maven-metadata.xml");
        }
    }

    private byte[] versionedJar(String version) throws Exception {
        Path source = Path.of(System.getProperty("bootui.cli.jar"));
        assertThat(source).isRegularFile();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarFile original = new JarFile(source.toFile())) {
            var manifest = original.getManifest();
            manifest.getMainAttributes().putValue("Implementation-Version", version);
            try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
                var entries = original.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
                        continue;
                    }
                    output.putNextEntry(new JarEntry(entry.getName()));
                    try (var input = original.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private Result process(List<String> command, Map<String, String> environment) throws Exception {
        Path output = Files.createTempFile(directory, "process-", ".log");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("CLI subprocess timed out: " + command);
        }
        return new Result(process.exitValue(), Files.readString(output));
    }

    private String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Result(int code, String output) {}
}
