package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmDependencyScanner.DependencySurvey;
import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraalVmDependencyScannerTests {

    @Test
    void reachabilityMetadataJsonCountsAsBundledMetadata(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "with-metadata.jar",
                Map.of("META-INF/native-image/com.example/app/reachability-metadata.json", "[]"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        assertThat(dependency.shipsMetadata()).isTrue();
        assertThat(dependency.note()).contains("reachability metadata");
    }

    @Test
    void legacyConfigJsonAlsoCountsAsBundledMetadata(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir, "legacy-config.jar", Map.of("META-INF/native-image/com.example/app/reflect-config.json", "[]"));

        assertThat(onlyDependency(jar).shipsMetadata()).isTrue();
    }

    @Test
    void nativeImagePropertiesAloneIsNotMetadata(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "build-args-only.jar",
                Map.of(
                        "META-INF/native-image/com.example/app/native-image.properties",
                        "Args = --initialize-at-build-time=com.example"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        assertThat(dependency.shipsMetadata()).isFalse();
        assertThat(dependency.note()).contains("native-image.properties");
    }

    @Test
    void jarWithoutNativeImageDirectoryHasNoMetadata(@TempDir Path dir) throws IOException {
        Path jar = jar(dir, "plain.jar", Map.of("com/example/App.class", "data"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        assertThat(dependency.shipsMetadata()).isFalse();
        assertThat(dependency.note()).contains("No bundled metadata");
    }

    @Test
    void surveyTruncatesAndFlagsTruncationWhenBoundIsExceeded(@TempDir Path dir) throws IOException {
        Path jar = jar(dir, "dep.jar", Map.of("com/example/App.class", "data"));
        int entries = GraalVmDependencyScanner.maxDependencies() + 5;
        String classPath = String.join(File.pathSeparator, Collections.nCopies(entries, jar.toString()));

        DependencySurvey survey = new GraalVmDependencyScanner(() -> classPath).scan();

        assertThat(survey.truncated()).isTrue();
        assertThat(survey.dependencies()).hasSize(GraalVmDependencyScanner.maxDependencies());
    }

    @Test
    void emptyClasspathProducesEmptyUntruncatedSurvey() {
        DependencySurvey survey = new GraalVmDependencyScanner(() -> "").scan();

        assertThat(survey.dependencies()).isEmpty();
        assertThat(survey.truncated()).isFalse();
    }

    private GraalVmDependencyDto onlyDependency(Path jar) {
        DependencySurvey survey = new GraalVmDependencyScanner(jar::toString).scan();
        assertThat(survey.truncated()).isFalse();
        assertThat(survey.dependencies()).hasSize(1);
        return survey.dependencies().get(0);
    }

    private static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
