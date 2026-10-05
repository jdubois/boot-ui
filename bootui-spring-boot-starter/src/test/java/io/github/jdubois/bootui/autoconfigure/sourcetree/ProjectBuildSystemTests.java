package io.github.jdubois.bootui.autoconfigure.sourcetree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildSystemTests {

    @Test
    void detectsMavenWrapperWhenMvnwPresent(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(projectRoot.resolve("mvnw"), "#!/bin/sh");

        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.MAVEN_WRAPPER);
    }

    @Test
    void detectsPlainMavenWhenOnlyPomPresent(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.MAVEN);
    }

    @Test
    void detectsGradleWrapperWhenGradlewPresent(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins {}");
        Files.writeString(projectRoot.resolve("gradlew"), "#!/bin/sh");

        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.GRADLE_WRAPPER);
    }

    @Test
    void detectsPlainGradleFromKotlinBuildScript(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), "plugins {}");

        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.GRADLE);
    }

    @Test
    void prefersMavenWhenBothBuildSystemsPresent(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(projectRoot.resolve("mvnw"), "#!/bin/sh");
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins {}");
        Files.writeString(projectRoot.resolve("gradlew"), "#!/bin/sh");

        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.MAVEN_WRAPPER);
    }

    @Test
    void defaultsToMavenWrapperWhenNoBuildFilesOrNullRoot(@TempDir Path projectRoot) {
        assertThat(ProjectBuildSystem.detect(projectRoot)).isEqualTo(ProjectBuildSystem.MAVEN_WRAPPER);
        assertThat(ProjectBuildSystem.detect(null)).isEqualTo(ProjectBuildSystem.MAVEN_WRAPPER);
    }

    @Test
    void classifiesMavenAndGradleAndWrapperFlags() {
        assertThat(ProjectBuildSystem.MAVEN_WRAPPER.usesMaven()).isTrue();
        assertThat(ProjectBuildSystem.MAVEN_WRAPPER.usesGradle()).isFalse();
        assertThat(ProjectBuildSystem.MAVEN_WRAPPER.hasWrapper()).isTrue();

        assertThat(ProjectBuildSystem.MAVEN.hasWrapper()).isFalse();

        assertThat(ProjectBuildSystem.GRADLE_WRAPPER.usesGradle()).isTrue();
        assertThat(ProjectBuildSystem.GRADLE_WRAPPER.usesMaven()).isFalse();
        assertThat(ProjectBuildSystem.GRADLE_WRAPPER.hasWrapper()).isTrue();

        assertThat(ProjectBuildSystem.GRADLE.hasWrapper()).isFalse();
    }

    @Test
    void wrapperSetupOnlyMarksTheWrapperExecutable() {
        assertThat(ProjectBuildSystem.MAVEN_WRAPPER.dockerSetup(DockerPackageManager.APT))
                .isEqualTo("# Make the Maven Wrapper executable.\nRUN chmod +x mvnw");
        assertThat(ProjectBuildSystem.GRADLE_WRAPPER.dockerSetup(DockerPackageManager.MICRODNF))
                .isEqualTo("# Make the Gradle Wrapper executable.\nRUN chmod +x gradlew");
    }

    @Test
    void aptSetupInstallsThePinnedBuildToolForDebianImages() {
        String maven = ProjectBuildSystem.MAVEN.dockerSetup(DockerPackageManager.APT);
        assertThat(maven).contains("ARG MAVEN_VERSION=" + ProjectBuildSystem.MAVEN_VERSION);
        assertThat(maven).contains("apt-get install -y --no-install-recommends curl ca-certificates");
        assertThat(maven).contains("apache-maven-${MAVEN_VERSION}-bin.tar.gz");
        assertThat(maven).doesNotContain("microdnf");

        String gradle = ProjectBuildSystem.GRADLE.dockerSetup(DockerPackageManager.APT);
        assertThat(gradle).contains("ARG GRADLE_VERSION=" + ProjectBuildSystem.GRADLE_VERSION);
        assertThat(gradle).contains("curl ca-certificates unzip");
        assertThat(gradle).contains("services.gradle.org");
    }

    @Test
    void microdnfSetupInstallsThePinnedBuildToolForUbiImages() {
        String maven = ProjectBuildSystem.MAVEN.dockerSetup(DockerPackageManager.MICRODNF);
        assertThat(maven).contains("microdnf install -y curl tar gzip && microdnf clean all");
        assertThat(maven).doesNotContain("apt-get");

        String gradle = ProjectBuildSystem.GRADLE.dockerSetup(DockerPackageManager.MICRODNF);
        assertThat(gradle).contains("microdnf install -y curl unzip && microdnf clean all");
    }
}
