package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootUiSampleApplicationTests {

    @Test
    void leavesDockerComposeDiscoveryAloneWhenRunningFromSampleModule(@TempDir Path workingDirectory) throws Exception {
        Files.createFile(workingDirectory.resolve("compose.yaml"));

        assertThat(BootUiSampleApplication.composeFileDefault(workingDirectory)).isEmpty();
    }

    @Test
    void pointsDockerComposeToSampleModuleWhenRunningFromRepositoryRoot(@TempDir Path workingDirectory)
            throws Exception {
        Path composeFile = Files.createDirectories(workingDirectory.resolve("bootui-sample-app"))
                .resolve("compose.yaml");
        Files.createFile(composeFile);

        assertThat(BootUiSampleApplication.composeFileDefault(workingDirectory)).contains(composeFile);
    }

    @Test
    void leavesDockerComposeDiscoveryAloneWhenNoSampleComposeFileExists(@TempDir Path workingDirectory) {
        assertThat(BootUiSampleApplication.composeFileDefault(workingDirectory)).isEmpty();
    }
}
