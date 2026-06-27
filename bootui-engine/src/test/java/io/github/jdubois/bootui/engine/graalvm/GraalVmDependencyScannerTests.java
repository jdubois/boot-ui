package io.github.jdubois.bootui.engine.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import io.github.jdubois.bootui.engine.graalvm.GraalVmDependencyScanner.DependencySurvey;
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
        assertThat(dependency.note()).contains("No bundled reachability metadata");
    }

    @Test
    void repositoryMetadataCoverageIsReportedForMatchingTestedVersion(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "postgresql-42.7.11.jar",
                Map.of(
                        "META-INF/maven/org.postgresql/postgresql/pom.properties",
                        "groupId=org.postgresql\nartifactId=postgresql\nversion=42.7.11\n"));

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates ->
                                ReachabilityMetadataIndex.of(java.util.List.of(new ReachabilityMetadataIndex.Entry(
                                        "42.7.3", java.util.List.of("42.7.3", "42.7.11"), true))))
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.shipsMetadata()).isFalse();
        assertThat(dependency.repositoryMetadata()).isTrue();
        assertThat(dependency.coordinates()).isEqualTo("org.postgresql:postgresql:42.7.11");
        assertThat(dependency.repositoryMetadataVersion()).isEqualTo("42.7.3");
        assertThat(dependency.repositoryUrl()).contains("metadata/org.postgresql/postgresql");
        assertThat(dependency.repositoryMetadataUrl()).contains("42.7.3/reachability-metadata.json");
        assertThat(dependency.note()).contains("repository covers 42.7.11");
        assertThat(dependency.note()).doesNotContain("No bundled");
    }

    @Test
    void repositoryMetadataLookupReportsUncoveredVersion(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "h2-9.9.9.jar",
                Map.of(
                        "META-INF/maven/com.h2database/h2/pom.properties",
                        "groupId=com.h2database\nartifactId=h2\nversion=9.9.9\n"));

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates ->
                                ReachabilityMetadataIndex.of(java.util.List.of(new ReachabilityMetadataIndex.Entry(
                                        "2.1.210", java.util.List.of("2.1.210", "2.4.240"), true))))
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.repositoryMetadata()).isFalse();
        assertThat(dependency.repositoryMetadataVersion()).isEqualTo("2.1.210");
        assertThat(dependency.repositoryTestedVersions()).contains("2.4.240");
        assertThat(dependency.repositoryUrl()).contains("metadata/com.h2database/h2");
        assertThat(dependency.repositoryMetadataUrl()).isNull();
        assertThat(dependency.note()).contains("metadata for this library, but not for 9.9.9");
        assertThat(dependency.note()).doesNotContain("No bundled");
        assertThat(dependency.note()).doesNotContain("Tested versions");
    }

    @Test
    void mavenRepositoryPathProvidesCoordinatesWhenPomPropertiesAreMissing(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir.resolve("repository/org/postgresql/postgresql/42.7.11"),
                "postgresql-42.7.11.jar",
                Map.of("com/example/App.class", "data"));

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates -> ReachabilityMetadataIndex.of(java.util.List.of(
                                new ReachabilityMetadataIndex.Entry("42.7.3", java.util.List.of("42.7.11"), true))))
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.coordinates()).isEqualTo("org.postgresql:postgresql:42.7.11");
        assertThat(dependency.repositoryMetadata()).isTrue();
    }

    @Test
    void repositoryLinkIsHiddenWhenRepositoryHasNoEntry(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "unknown-1.0.0.jar",
                Map.of(
                        "META-INF/maven/org.example/unknown/pom.properties",
                        "groupId=org.example\nartifactId=unknown\nversion=1.0.0\n"));

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString, coordinates -> ReachabilityMetadataIndex.of(java.util.List.of()))
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.repositoryMetadata()).isFalse();
        assertThat(dependency.repositoryUrl()).isNull();
        assertThat(dependency.repositoryMetadataUrl()).isNull();
        assertThat(dependency.note()).contains("has no entry for this library");
    }

    @Test
    void repositoryLookupIsSkippedWhenDisabled(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "postgresql-42.7.11.jar",
                Map.of(
                        "META-INF/maven/org.postgresql/postgresql/pom.properties",
                        "groupId=org.postgresql\nartifactId=postgresql\nversion=42.7.11\n"));

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates -> {
                            throw new AssertionError("repository lookup must not run when disabled");
                        },
                        new java.util.concurrent.atomic.AtomicReference<>(GraalVmDependencyScanner.Progress.idle()),
                        new java.util.concurrent.atomic.AtomicBoolean(false),
                        false,
                        GraalVmDependencyScanner.maxDependencies())
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.repositoryMetadata()).isFalse();
        assertThat(dependency.repositoryUrl()).isNull();
        assertThat(dependency.repositoryMetadataUrl()).isNull();
        assertThat(dependency.note()).contains("repository lookup is disabled");
    }

    @Test
    void repositoryLookupStopsAfterTheConfiguredLimit(@TempDir Path dir) throws IOException {
        Path covered = jar(
                dir,
                "postgresql-42.7.11.jar",
                Map.of(
                        "META-INF/maven/org.postgresql/postgresql/pom.properties",
                        "groupId=org.postgresql\nartifactId=postgresql\nversion=42.7.11\n"));
        Path second = jar(
                dir,
                "h2-2.2.224.jar",
                Map.of(
                        "META-INF/maven/com.h2database/h2/pom.properties",
                        "groupId=com.h2database\nartifactId=h2\nversion=2.2.224\n"));
        String classPath = covered + File.pathSeparator + second;
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();

        DependencySurvey survey = new GraalVmDependencyScanner(
                        () -> classPath,
                        coordinates -> {
                            lookups.incrementAndGet();
                            return ReachabilityMetadataIndex.of(java.util.List.of(new ReachabilityMetadataIndex.Entry(
                                    coordinates.version(), java.util.List.of(coordinates.version()), true)));
                        },
                        new java.util.concurrent.atomic.AtomicReference<>(GraalVmDependencyScanner.Progress.idle()),
                        new java.util.concurrent.atomic.AtomicBoolean(false),
                        true,
                        1)
                .scan();

        assertThat(lookups.get()).isEqualTo(1);
        assertThat(survey.dependencies()).hasSize(2);
        assertThat(survey.dependencies().stream()
                        .filter(GraalVmDependencyDto::repositoryMetadata)
                        .count())
                .isEqualTo(1);
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
        Files.createDirectories(dir);
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
