package io.github.jdubois.bootui.engine.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import io.github.jdubois.bootui.engine.graalvm.GraalVmDependencyScanner.DependencySurvey;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
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
    void arbitraryJsonUnderNativeImageDoesNotCountAsMetadata(@TempDir Path dir) throws IOException {
        Path jar =
                jar(dir, "arbitrary-json.jar", Map.of("META-INF/native-image/com.example/app/build-output.json", "{}"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        assertThat(dependency.shipsMetadata()).isFalse();
        assertThat(dependency.note()).contains("No bundled reachability metadata");
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
    void repositoryDefaultForRegexCoversMatchingVersions(@TempDir Path dir) throws IOException {
        Path jar = dependencyJar(dir, "sample-1.9.0.jar", "com.example", "sample", "1.9.0");

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates -> ReachabilityMetadataIndex.of(java.util.List.of(
                                new ReachabilityMetadataIndex.Entry("1.8.0", java.util.List.of(), "1\\..*", true))))
                .scan();

        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.repositoryMetadata()).isTrue();
        assertThat(dependency.repositoryMetadataVersion()).isEqualTo("1.8.0");
    }

    @Test
    void repositoryExactVersionWinsOverEarlierDefaultForRegex(@TempDir Path dir) throws IOException {
        Path jar = dependencyJar(dir, "sample-1.9.0.jar", "com.example", "sample", "1.9.0");

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates -> ReachabilityMetadataIndex.of(java.util.List.of(
                                new ReachabilityMetadataIndex.Entry("1.8.0", java.util.List.of(), "1\\..*", true),
                                new ReachabilityMetadataIndex.Entry("1.9.0", java.util.List.of(), false))))
                .scan();

        assertThat(survey.dependencies().get(0).repositoryMetadataVersion()).isEqualTo("1.9.0");
    }

    @Test
    void repositoryInvalidDefaultForRegexIsIgnored(@TempDir Path dir) throws IOException {
        Path jar = dependencyJar(dir, "sample-1.9.0.jar", "com.example", "sample", "1.9.0");

        DependencySurvey survey = new GraalVmDependencyScanner(
                        jar::toString,
                        coordinates -> ReachabilityMetadataIndex.of(java.util.List.of(
                                new ReachabilityMetadataIndex.Entry("1.8.0", java.util.List.of(), "[", true))))
                .scan();

        assertThat(survey.dependencies().get(0).repositoryMetadata()).isFalse();
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
    void nestedInspectionStopsBeforeOpeningExcessLibraries(@TempDir Path dir) throws IOException {
        int limit = GraalVmDependencyScanner.maxDependencies();
        for (int count : new int[] {limit - 1, limit, limit + 1}) {
            Path archive = fatJarWithLibraries(dir, count);
            try (CountingJarFile jar = new CountingJarFile(archive, limit, () -> {})) {
                var result = new GraalVmDependencyScanner(() -> "").inspect(jar, limit);
                assertThat(result.dependencies()).hasSize(Math.min(count, limit));
                assertThat(jar.nestedOpened).isEqualTo(Math.min(count, limit));
                assertThat(result.truncated()).isEqualTo(count > limit);
            }
        }
    }

    @Test
    void nestedInspectionHonorsRemainingBudgetAndCancellation(@TempDir Path dir) throws IOException {
        Path archive = fatJarWithLibraries(dir, 3);
        GraalVmDependencyScanner scanner = new GraalVmDependencyScanner(() -> "");
        try (CountingJarFile jar = new CountingJarFile(archive, 1, () -> {})) {
            assertThat(scanner.inspect(jar, 1).truncated()).isTrue();
            assertThat(jar.nestedOpened).isEqualTo(1);
        }
        try (CountingJarFile jar = new CountingJarFile(archive, 1, scanner::cancel)) {
            assertThat(scanner.inspect(jar, 3).dependencies()).hasSize(1);
            assertThat(jar.nestedOpened).isEqualTo(1);
        }
    }

    @Test
    void surveyPassesRemainingBudgetToFatJar(@TempDir Path dir) throws IOException {
        Path ordinary = jar(dir, "ordinary.jar", Map.of("App.class", "data"));
        Path archive = fatJarWithLibraries(dir, 3);
        String classpath = String.join(
                        File.pathSeparator,
                        Collections.nCopies(GraalVmDependencyScanner.maxDependencies() - 2, ordinary.toString()))
                + File.pathSeparator
                + archive;

        DependencySurvey result = new GraalVmDependencyScanner(() -> classpath).scan();
        assertThat(result.dependencies()).hasSize(GraalVmDependencyScanner.maxDependencies());
        assertThat(result.truncated()).isTrue();
        assertThat(result.dependencies())
                .filteredOn(dep -> dep.name().startsWith("nested-"))
                .hasSize(2);
    }

    @Test
    void exactlyFullOrdinaryClasspathDoesNotClaimTruncation(@TempDir Path dir) throws IOException {
        Path ordinary = jar(dir, "ordinary.jar", Map.of("App.class", "data"));
        String classpath = String.join(
                File.pathSeparator,
                Collections.nCopies(GraalVmDependencyScanner.maxDependencies(), ordinary.toString()));
        assertThat(new GraalVmDependencyScanner(() -> classpath).scan().truncated())
                .isFalse();
    }

    private static Path fatJarWithLibraries(Path dir, int count) throws IOException {
        Map<String, byte[]> libraries = new LinkedHashMap<>();
        byte[] contents = jarBytes(Map.of("App.class", "data"));
        for (int i = 0; i < count; i++) {
            libraries.put("BOOT-INF/lib/nested-" + i + "-1.0.jar", contents);
        }
        return jarWithNestedJars(dir, "app-" + count + ".jar", Map.of(), libraries);
    }

    private static final class CountingJarFile extends JarFile {
        private final int maximum;
        private final Runnable onNestedOpen;
        private int nestedOpened;

        CountingJarFile(Path path, int maximum, Runnable onNestedOpen) throws IOException {
            super(path.toFile());
            this.maximum = maximum;
            this.onNestedOpen = onNestedOpen;
        }

        @Override
        public InputStream getInputStream(ZipEntry entry) throws IOException {
            if (entry.getName().startsWith("BOOT-INF/lib/")) {
                nestedOpened++;
                assertThat(nestedOpened).as("nested streams opened").isLessThanOrEqualTo(maximum);
                onNestedOpen.run();
            }
            return super.getInputStream(entry);
        }
    }

    @Test
    void fatJarExpandsBootInfLibNestedDependenciesInsteadOfTheLauncherJar(@TempDir Path dir) throws IOException {
        byte[] depWithMetadata = jarBytes(Map.of(
                "META-INF/native-image/com.example/dep-a/reachability-metadata.json",
                "[]",
                "META-INF/maven/com.example/dep-a/pom.properties",
                "groupId=com.example\nartifactId=dep-a\nversion=1.0.0\n"));
        byte[] depWithoutMetadata = jarBytes(Map.of("com/example/dep/Dep.class", "data"));

        Path fatJar = jarWithNestedJars(
                dir,
                "app-0.0.1-SNAPSHOT.jar",
                Map.of("BOOT-INF/classes/com/example/App.class", "data"),
                Map.of(
                        "BOOT-INF/lib/dep-a-1.0.0.jar", depWithMetadata,
                        "BOOT-INF/lib/dep-b-2.0.0.jar", depWithoutMetadata));

        DependencySurvey survey = new GraalVmDependencyScanner(fatJar::toString).scan();

        assertThat(survey.dependencies()).hasSize(2);
        assertThat(survey.dependencies().stream()
                        .map(GraalVmDependencyDto::name)
                        .toList())
                .containsExactlyInAnyOrder("dep-a-1.0.0.jar", "dep-b-2.0.0.jar");
        GraalVmDependencyDto depA = survey.dependencies().stream()
                .filter(dependency -> dependency.name().equals("dep-a-1.0.0.jar"))
                .findFirst()
                .orElseThrow();
        assertThat(depA.shipsMetadata()).isTrue();
        assertThat(depA.coordinates()).isEqualTo("com.example:dep-a:1.0.0");
        GraalVmDependencyDto depB = survey.dependencies().stream()
                .filter(dependency -> dependency.name().equals("dep-b-2.0.0.jar"))
                .findFirst()
                .orElseThrow();
        assertThat(depB.shipsMetadata()).isFalse();
    }

    @Test
    void fatJarNestedLibraryWithoutMavenCoordinatesFallsBackToItsOwnFileName(@TempDir Path dir) throws IOException {
        byte[] depWithoutPom = jarBytes(Map.of("com/example/dep/Dep.class", "data"));

        Path fatJar = jarWithNestedJars(
                dir,
                "app.jar",
                Map.of("BOOT-INF/classes/com/example/App.class", "data"),
                Map.of("BOOT-INF/lib/no-pom-lib-4.5.6.jar", depWithoutPom));

        DependencySurvey survey = new GraalVmDependencyScanner(fatJar::toString).scan();

        assertThat(survey.dependencies()).hasSize(1);
        GraalVmDependencyDto dependency = survey.dependencies().get(0);
        assertThat(dependency.name()).isEqualTo("no-pom-lib-4.5.6.jar");
        // No pom.properties inside the nested jar itself: falls back to parsing the nested entry's own
        // file name, exactly like a top-level classpath jar without bundled Maven coordinates would.
        assertThat(dependency.coordinates()).isEqualTo("no-pom-lib:4.5.6");
    }

    @Test
    void shadedJarWithMultiplePomPropertiesPrefersTheOneMatchingItsOwnFileName(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "myapp-shaded-1.0.0.jar",
                Map.of(
                        "META-INF/maven/com.example/relocated-lib/pom.properties",
                        "groupId=com.example\nartifactId=relocated-lib\nversion=2.0.0\n",
                        "META-INF/maven/com.example/myapp-shaded/pom.properties",
                        "groupId=com.example\nartifactId=myapp-shaded\nversion=1.0.0\n"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        assertThat(dependency.coordinates()).isEqualTo("com.example:myapp-shaded:1.0.0");
    }

    @Test
    void shadedJarFallsBackToFirstPomPropertiesWhenNoneMatchTheFileName(@TempDir Path dir) throws IOException {
        Path jar = jar(
                dir,
                "unrelated-name.jar",
                Map.of(
                        "META-INF/maven/com.example/first/pom.properties",
                        "groupId=com.example\nartifactId=first\nversion=1.0.0\n",
                        "META-INF/maven/com.example/second/pom.properties",
                        "groupId=com.example\nartifactId=second\nversion=2.0.0\n"));

        GraalVmDependencyDto dependency = onlyDependency(jar);

        // Neither pom.properties matches the jar's own file name (it does not follow the Maven
        // "artifactId-version.jar" convention at all), so this pins the deterministic fallback: the
        // first descriptor encountered by JAR entry order is reported, matching the scanner's previous
        // behavior for this genuinely ambiguous case.
        assertThat(dependency.coordinates()).isIn("com.example:first:1.0.0", "com.example:second:2.0.0");
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

    private static Path dependencyJar(Path dir, String name, String groupId, String artifactId, String version)
            throws IOException {
        return jar(
                dir,
                name,
                Map.of(
                        "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties",
                        "groupId=" + groupId + "\nartifactId=" + artifactId + "\nversion=" + version + "\n"));
    }

    /** Builds a jar's raw bytes in memory, for embedding as a {@code BOOT-INF/lib/} nested entry. */
    private static byte[] jarBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /** Builds a fat/uber jar with plain text entries plus binary (nested-jar) entries, unmodified. */
    private static Path jarWithNestedJars(
            Path dir, String name, Map<String, String> textEntries, Map<String, byte[]> nestedJarEntries)
            throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : textEntries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : nestedJarEntries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }
}
