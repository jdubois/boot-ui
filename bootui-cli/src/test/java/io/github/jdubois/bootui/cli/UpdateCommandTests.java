package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCommandTests {
    @TempDir
    Path directory;

    @Test
    void checkIsFreshReadOnlyAndHasDistinctExitCodesEvenWhenPassiveChecksAreDisabled() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install = UpdateTestRepository.install(directory, repository.url(), true, false);
            byte[] before = Files.readAllBytes(install.jar());
            for (String version : new String[] {"1.0.0", "0.9.0", "2.0.0"}) {
                repository.metadata(version);
                Result result = run(repository, install, true);
                assertThat(result.code()).isEqualTo(version.equals("2.0.0") ? 3 : 0);
                assertThat(result.json().get("status").asString(""))
                        .isEqualTo(version.equals("2.0.0") ? "available" : "current");
                assertThat(Files.readAllBytes(install.jar())).isEqualTo(before);
            }
            assertThat(repository.requests).hasSize(3).allMatch(path -> path.endsWith("maven-metadata.xml"));
            assertThat(install.jar().getParent().resolve("update.lock")).doesNotExist();
            assertThat(directory.resolve("update-checked-at")).doesNotExist();
        }
    }

    @Test
    void verifiedUpdateReplacesOnlyTheManagedJarAndRetainsTheLauncher() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install = UpdateTestRepository.install(directory, repository.url(), true, false);
            String launcher = Files.readString(install.launcher());
            byte[] replacement = UpdateTestRepository.jar("2.0.0");
            repository.metadata("2.0.0");
            repository.artifact("2.0.0", replacement, "SHA-512");
            assertThat(run(repository, install, false).json().get("status").asString(""))
                    .isEqualTo("updated");
            assertThat(Files.readAllBytes(install.jar())).isEqualTo(replacement);
            assertThat(Files.readString(install.launcher())).isEqualTo(launcher);
            UpdateCommand.validateJar(install.jar(), "2.0.0");
            try (var paths = Files.list(directory)) {
                assertThat(paths.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".bootui-update"));
            }
        }
    }

    @Test
    void checksumAndJarFailuresPreserveTheOldInstallation() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install = UpdateTestRepository.install(directory, repository.url(), true, false);
            byte[] before = Files.readAllBytes(install.jar());
            repository.metadata("2.0.0");
            repository.artifact("2.0.0", new byte[] {1, 2, 3}, "SHA-512");
            Result invalid = run(repository, install, false);
            assertThat(invalid.code()).isEqualTo(1);
            assertThat(invalid.json().get("status").asString("")).isEqualTo("failed");
            assertThat(Files.readAllBytes(install.jar())).isEqualTo(before);
            repository.responses.keySet().removeIf(path -> path.endsWith(".sha512"));
            assertThat(run(repository, install, false).code()).isEqualTo(1);
            assertThat(Files.readAllBytes(install.jar())).isEqualTo(before);
        }
    }

    @Test
    void concurrentUpdatesAndOwnershipChangesRefuseWithoutReplacement() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install = UpdateTestRepository.install(directory, repository.url(), true, false);
            repository.metadata("2.0.0");
            byte[] before = Files.readAllBytes(install.jar());
            try (var channel = FileChannel.open(
                            directory.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                assertThat(lock.isValid()).isTrue();
                assertThat(run(repository, install, false).code()).isEqualTo(1);
            }
            Files.writeString(install.launcher(), "not the installer");
            assertThat(run(repository, install, false).code()).isEqualTo(1);
            assertThat(Files.readAllBytes(install.jar())).isEqualTo(before);
        }
    }

    @Test
    void jbangAndUnmanagedInstallsNeverDownloadOrReplaceAJar() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            repository.metadata("2.0.0");
            Path jar = directory.resolve("manual.jar");
            Files.write(jar, UpdateTestRepository.jar("1.0.0"));
            byte[] before = Files.readAllBytes(jar);
            var jbang = new Installation("jbang", jar, null, null);
            Result guidance = run(repository, jbang, false);
            assertThat(guidance.code()).isZero();
            assertThat(guidance.json().get("status").asString("")).isEqualTo("manual-action-required");
            assertThat(guidance.json().get("command").asString("")).isEqualTo(Installation.JBANG_COMMAND);
            assertThat(run(repository, new Installation("unmanaged", jar, null, null), false)
                            .code())
                    .isEqualTo(1);
            assertThat(Files.readAllBytes(jar)).isEqualTo(before);
            assertThat(repository.requests).hasSize(2).allMatch(path -> path.endsWith("maven-metadata.xml"));
        }
    }

    @Test
    void windowsPrintsReviewableInstallerGuidanceBeforeAnyDownload() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install =
                    UpdateTestRepository.install(directory.resolve("user's install"), repository.url(), true, true);
            repository.metadata("2.0.0");
            byte[] before = Files.readAllBytes(install.jar());
            Result result = run(repository, install, false, true);
            assertThat(result.code()).isZero();
            assertThat(result.json().get("status").asString("")).isEqualTo("manual-action-required");
            assertThat(result.json().get("message").asString(""))
                    .contains("Download and review", "after the CLI exits");
            String quotedDirectory = "'" + install.jar().getParent().toString().replace("'", "''") + "'";
            assertThat(result.json().get("command").asString(""))
                    .contains(
                            "& .\\install.ps1",
                            "-Version '2.0.0'",
                            "-InstallDir " + quotedDirectory,
                            "-BinDir " + quotedDirectory,
                            "-MavenRepo '" + repository.url() + "'",
                            "-NoPathUpdate");
            assertThat(repository.requests).singleElement().asString().endsWith("maven-metadata.xml");
            assertThat(Files.readAllBytes(install.jar())).isEqualTo(before);
            assertThat(install.jar().getParent().resolve("update.lock")).doesNotExist();
        }
    }

    @Test
    void filesystemFailuresNameTheOperationInsteadOfOnlyAPath() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var install = UpdateTestRepository.install(directory, repository.url(), true, false);
            repository.metadata("2.0.0");
            Files.createDirectory(directory.resolve("update.lock"));
            Result result = run(repository, install, false);
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.json().get("message").asString(""))
                    .contains("Cannot update the installation", "permissions", "locks");
        }
    }

    private Result run(UpdateTestRepository repository, Installation install, boolean check) {
        return run(repository, install, check, false);
    }

    private Result run(UpdateTestRepository repository, Installation install, boolean check, boolean windows) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliContext context = new CliContext(
                ToolManifest.bundled(),
                Map.of("BOOTUI_MAVEN_REPO", repository.url(), "BOOTUI_NO_UPDATE_CHECK", "1"),
                false,
                new PrintWriter(out),
                new PrintWriter(err),
                options -> {
                    throw new AssertionError("Update must not create an application client.");
                });
        int code = new UpdateCommand(context, "1.0.0", install, windows).run(check);
        assertThat(err.toString()).isEmpty();
        return new Result(code, JsonValue.parse(out.toString()));
    }

    private record Result(int code, JsonValue json) {}
}
