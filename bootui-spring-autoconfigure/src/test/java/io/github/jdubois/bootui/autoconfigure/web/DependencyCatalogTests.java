package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

class DependencyCatalogTests {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------------------------------
    // Coordinate resolution
    // -----------------------------------------------------------------------------------------------

    @Test
    void discoversMavenCoordinatesFromJavaClassPathJarsWithoutPomProperties() {
        List<DependencyDto> dependencies = withClassPath(
                emptyResolver(),
                "/home/user/.m2/repository/org/apache/tomcat/embed/tomcat-embed-core/11.0.21/tomcat-embed-core-11.0.21.jar",
                "/home/user/.m2/repository/org/postgresql/postgresql/42.7.10/postgresql-42.7.10.jar");

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version)
                .contains(
                        tuple("org.apache.tomcat.embed:tomcat-embed-core", "11.0.21"),
                        tuple("org.postgresql:postgresql", "42.7.10"));
    }

    @Test
    void discoversCoordinatesFromAnAdjacentPomInANonstandardRepositoryPath() throws Exception {
        Path versionDirectory = tempDir.resolve("custom-cache/acme-widget/2.1.0");
        Files.createDirectories(versionDirectory);
        Path jar = Files.createFile(versionDirectory.resolve("acme-widget-2.1.0.jar"));
        Files.writeString(versionDirectory.resolve("acme-widget-2.1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.libs</groupId>
                  <artifactId>acme-widget</artifactId>
                  <version>2.1.0</version>
                </project>
                """);

        List<DependencyDto> dependencies = withClassPath(emptyResolver(), jar.toString());

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version, DependencyDto::source)
                .containsExactly(tuple("com.acme.libs:acme-widget", "2.1.0", "Adjacent Maven POM"));
    }

    @Test
    void doesNotInventAGroupIdForANonstandardPathWithoutMavenMetadata() throws Exception {
        Path versionDirectory = tempDir.resolve("custom-cache/acme-widget/2.1.0");
        Files.createDirectories(versionDirectory);
        Path jar = Files.createFile(versionDirectory.resolve("acme-widget-2.1.0.jar"));

        assertThat(withClassPath(emptyResolver(), jar.toString())).isEmpty();
    }

    @Test
    void skipsUnreadableMavenMetadataWithoutDiscardingReadableEntries() {
        Resource readable = new ByteArrayResource(
                "groupId=com.acme\nartifactId=widget\nversion=1.2.3\n".getBytes(StandardCharsets.UTF_8));
        Resource unreadable = failingResource("unreadable pom.properties");
        ResourcePatternResolver resolver =
                patternResolver(Map.of("classpath*:META-INF/maven/*/*/pom.properties", List.of(readable, unreadable)));

        List<DependencyDto> dependencies = new DependencyCatalog(resolver).dependencies();

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version)
                .contains(tuple("com.acme:widget", "1.2.3"));
    }

    @Test
    void rejectsJarNamesWhoseVersionOnlySharesAStringPrefix() throws Exception {
        Path versionDirectory = tempDir.resolve("repository/org/example/widget/1.0");
        Files.createDirectories(versionDirectory);
        Path wrongVersionJar = Files.createFile(versionDirectory.resolve("widget-1.0.1.jar"));

        assertThat(withClassPath(emptyResolver(), wrongVersionJar.toString())).isEmpty();
    }

    // -----------------------------------------------------------------------------------------------
    // CycloneDX SBOM
    // -----------------------------------------------------------------------------------------------

    @Test
    void resolvesCoordinatesFromTheEmbeddedCycloneDxSbom() {
        ResourcePatternResolver resolver = sbomResolver("""
                {
                  "bomFormat": "CycloneDX",
                  "metadata": {"component": {"purl": "pkg:maven/com.example/demo-app@0.0.1"}},
                  "components": [
                    {"purl": "pkg:maven/org.springframework/spring-core@7.0.9"},
                    {"purl": "pkg:maven/org.hibernate.orm/hibernate-core@7.4.5.Final"},
                    {"purl": "pkg:npm/left-pad@1.3.0"},
                    {"name": "no purl at all"}
                  ]
                }
                """);

        List<DependencyDto> dependencies = withClassPath(resolver, "");

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version, DependencyDto::source)
                .containsExactly(
                        tuple("org.hibernate.orm:hibernate-core", "7.4.5.Final", "CycloneDX SBOM"),
                        tuple("org.springframework:spring-core", "7.0.9", "CycloneDX SBOM"));
    }

    @Test
    void sbomCoordinatesTakePrecedenceOverOtherSourcesForTheSameArtifact() {
        Resource pomProperties =
                new ByteArrayResource("groupId=org.springframework\nartifactId=spring-core\nversion=7.0.9\n"
                        .getBytes(StandardCharsets.UTF_8));
        ResourcePatternResolver resolver = patternResolver(Map.of(
                "classpath*:META-INF/sbom/application.cdx.json",
                List.of(sbomResource("""
                        {"components": [{"purl": "pkg:maven/org.springframework/spring-core@7.0.9"}]}
                        """)),
                "classpath*:META-INF/maven/*/*/pom.properties",
                List.of(pomProperties)));

        List<DependencyDto> dependencies = withClassPath(resolver, "");

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::source)
                .containsExactly(tuple("org.springframework:spring-core", "CycloneDX SBOM"));
    }

    @Test
    void aMalformedSbomIsSkippedWithoutDiscardingTheRestOfTheInventory() {
        Resource pomProperties = new ByteArrayResource(
                "groupId=com.acme\nartifactId=widget\nversion=1.2.3\n".getBytes(StandardCharsets.UTF_8));
        ResourcePatternResolver resolver = patternResolver(Map.of(
                "classpath*:META-INF/sbom/application.cdx.json",
                List.of(sbomResource("{ this is not json")),
                "classpath*:META-INF/sbom/bom.json",
                List.of(failingResource("unreadable bom.json")),
                "classpath*:META-INF/maven/*/*/pom.properties",
                List.of(pomProperties)));

        List<DependencyDto> dependencies = withClassPath(resolver, "");

        assertThat(dependencies).extracting(DependencyDto::packageName).containsExactly("com.acme:widget");
    }

    // -----------------------------------------------------------------------------------------------
    // Coverage
    // -----------------------------------------------------------------------------------------------

    @Test
    void reportsUnidentifiedNestedLibrariesOfARepackagedArchiveInsteadOfHidingThem() throws Exception {
        Path fatJar = repackagedJar(
                "app.jar", "BOOT-INF/lib/", List.of("resolved-1.0.0.jar", "mystery-2.0.0.jar", "other-3.0.0.jar"));
        ResourcePatternResolver resolver = sbomResolver("""
                {"components": [{"purl": "pkg:maven/com.example/resolved@1.0.0"}]}
                """);

        DependencyInventory inventory = withClassPathInventory(resolver, fatJar.toString());

        assertThat(inventory.dependencies())
                .extracting(DependencyDto::packageName)
                .containsExactly("com.example:resolved");
        DependencyCoverageDto coverage = inventory.coverage();
        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.INCOMPLETE);
        assertThat(coverage.archivesFound()).isEqualTo(3);
        assertThat(coverage.archivesIdentified()).isEqualTo(1);
        assertThat(coverage.archivesUnidentified()).isEqualTo(2);
        assertThat(coverage.unidentifiedArchives()).containsExactly("mystery-2.0.0.jar", "other-3.0.0.jar");
        assertThat(coverage.unidentifiedArchivesTruncated()).isFalse();
        // The outer application archive is not a dependency, so it is never counted as an unscanned JAR.
        assertThat(coverage.unidentifiedArchives()).doesNotContain("app.jar");
    }

    @Test
    void reportsCompleteCoverageWhenEveryNestedLibraryResolves() throws Exception {
        Path fatJar = repackagedJar("app.jar", "BOOT-INF/lib/", List.of("resolved-1.0.0.jar", "other-3.0.0.jar"));
        ResourcePatternResolver resolver = sbomResolver("""
                {"components": [
                  {"purl": "pkg:maven/com.example/resolved@1.0.0"},
                  {"purl": "pkg:maven/com.example/other@3.0.0"}
                ]}
                """);

        DependencyCoverageDto coverage =
                withClassPathInventory(resolver, fatJar.toString()).coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.COMPLETE);
        assertThat(coverage.archivesFound()).isEqualTo(2);
        assertThat(coverage.archivesUnidentified()).isZero();
        assertThat(coverage.unidentifiedArchives()).isEmpty();
    }

    @Test
    void honoursTheSpringBootLibManifestAttributeWhenLocatingNestedLibraries() throws Exception {
        Path warLikeJar = repackagedJar("app.war", "WEB-INF/lib/", List.of("mystery-2.0.0.jar"));

        DependencyCoverageDto coverage =
                withClassPathInventory(emptyResolver(), warLikeJar.toString()).coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.INCOMPLETE);
        assertThat(coverage.unidentifiedArchives()).containsExactly("mystery-2.0.0.jar");
    }

    @Test
    void countsPlainClasspathJarsWhenTheApplicationRunsExploded() throws Exception {
        Path resolvedJar = plainJar("resolved-1.0.0.jar");
        Path mysteryJar = plainJar("mystery-2.0.0.jar");
        ResourcePatternResolver resolver = sbomResolver("""
                {"components": [{"purl": "pkg:maven/com.example/resolved@1.0.0"}]}
                """);

        DependencyCoverageDto coverage = withClassPathInventory(
                        resolver,
                        resolvedJar.toString(),
                        mysteryJar.toString(),
                        tempDir.resolve("classes").toString())
                .coverage();

        assertThat(coverage.archivesFound()).isEqualTo(2);
        assertThat(coverage.unidentifiedArchives()).containsExactly("mystery-2.0.0.jar");
    }

    @Test
    void countsExplodedLauncherArchivesSeparatelyFromTheCompleteSbomInventory() throws Exception {
        List<String> archives = IntStream.range(0, 325)
                .mapToObj(i -> "library-" + i + "-1.0.jar")
                .toList();
        String components = IntStream.range(0, 520)
                .mapToObj(i -> "{\"purl\":\"pkg:maven/com.example/library-" + i + "@1.0\"}")
                .collect(Collectors.joining(","));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = explodedLoader("{\"components\":[" + components + "]}", archives)) {
            Thread.currentThread().setContextClassLoader(loader);

            DependencyInventory inventory = withClassPathInventory(new DependencyCatalog(), ".");

            assertThat(inventory.dependencies())
                    .hasSize(520)
                    .allMatch(d -> d.source().equals("CycloneDX SBOM"));
            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(325, 0, List.of()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void reportsUnidentifiedExplodedLibrariesEvenWhenTheSbomWasRead() throws Exception {
        try (URLClassLoader loader = explodedLoader(
                "{\"components\":[{\"purl\":\"pkg:maven/com.example/resolved@1.0\"}]}",
                List.of("resolved-1.0.jar", "mystery-2.0.jar"))) {
            DependencyInventory inventory =
                    withClassPathInventory(new PathMatchingResourcePatternResolver(loader), ".");

            assertThat(inventory.dependencies())
                    .extracting(DependencyDto::packageName)
                    .containsExactly("com.example:resolved");
            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(2, 1, List.of("mystery-2.0.jar")));
        }
    }

    @Test
    void identifiesExplodedLibrariesFromMavenDescriptorsWithoutAnSbom() throws Exception {
        Path described = tempDir.resolve("BOOT-INF/lib/renamed-bundle.jar");
        Files.createDirectories(described.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(described))) {
            for (String directory : List.of(
                    "META-INF/", "META-INF/maven/", "META-INF/maven/com.acme/", "META-INF/maven/com.acme/widget/")) {
                jar.putNextEntry(new ZipEntry(directory));
                jar.closeEntry();
            }
            jar.putNextEntry(new ZipEntry("META-INF/maven/com.acme/widget/pom.properties"));
            jar.write(WIDGET_DESCRIPTOR);
            jar.closeEntry();
        }
        Path mystery = plainJar("BOOT-INF/lib/mystery-1.0.jar");
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {described.toUri().toURL(), mystery.toUri().toURL()}, null)) {
            var resolver = new PathMatchingResourcePatternResolver(loader);
            // The temporary JAR must not outlive the loader in Spring's URL connection cache.
            resolver.setUseCaches(false);
            DependencyInventory inventory = withClassPathInventory(resolver, ".");

            assertThat(inventory.dependencies())
                    .extracting(DependencyDto::packageName)
                    .containsExactly("com.acme:widget");
            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(2, 1, List.of("mystery-1.0.jar")));
        }
        Files.delete(described);
        Files.delete(mystery);
    }

    @Test
    void traversesNonUrlWrappersAndParentLoadersEvenWithABlankClassPath() throws Exception {
        Path parentJar = plainJar("parent-1.0.jar");
        Path childJar = plainJar("child-1.0.jar");
        try (URLClassLoader parent =
                        new URLClassLoader(new URL[] {parentJar.toUri().toURL()}, null);
                URLClassLoader child =
                        new URLClassLoader(new URL[] {childJar.toUri().toURL()}, parent)) {
            ClassLoader wrapper = new ClassLoader(child) {};
            DependencyInventory inventory = withClassPathInventory(patternResolver(Map.of(), wrapper), "");

            assertThat(inventory.coverage())
                    .isEqualTo(DependencyCoverageDto.of(2, 2, List.of("child-1.0.jar", "parent-1.0.jar")));
        }
    }

    @Test
    void countsTheSameArchiveOnlyOnceAcrossClassPathAndLoaderEntries() throws Exception {
        Path jar = plainJar("library-1.0.jar");
        try (URLClassLoader parent = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
                URLClassLoader child = new URLClassLoader(new URL[] {jar.toUri().toURL()}, parent)) {
            DependencyInventory inventory = withClassPathInventory(patternResolver(Map.of(), child), jar.toString());

            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(1, 1, List.of("library-1.0.jar")));
        }
    }

    @Test
    void decodesLocalUrlsWithoutTurningLiteralPlusCharactersIntoSpaces() throws Exception {
        try (URLClassLoader loader = explodedLoader(
                "{\"components\":[{\"group\":\"com.example\",\"purl\":\"pkg:maven/com.example/lib@1.0%2Bbuild\"}]}",
                List.of("lib-1.0+build.jar", "name with spaces.jar"))) {
            DependencyInventory inventory =
                    withClassPathInventory(new PathMatchingResourcePatternResolver(loader), ".");

            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(2, 1, List.of("name with spaces.jar")));
        }
    }

    @Test
    void ignoresNonFileUrlsAndPreservesLocalArchivesAfterAMalformedFileUrl() throws Exception {
        URL remote = new URL(null, "https://example.invalid/library.jar", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                throw new AssertionError("The archive census must not open remote URLs");
            }
        });
        Path local = plainJar("local-1.0.jar");
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {
                    remote, new URL("file:/invalid%zz.jar"), local.toUri().toURL()
                },
                null)) {
            DependencyInventory inventory = withClassPathInventory(patternResolver(Map.of(), loader), "");

            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(1, 1, List.of("local-1.0.jar")));
        }
    }

    @Test
    void inspectsRepackagedArchivesFoundOnlyInTheLoader() throws Exception {
        Path jar = repackagedJar("app.war", "WEB-INF/lib/", List.of("nested-1.0.jar"));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            DependencyInventory inventory = withClassPathInventory(patternResolver(Map.of(), loader), ".");

            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(1, 1, List.of("nested-1.0.jar")));
        }
    }

    @Test
    void anUnreadableLoaderArchiveIsStillReportedAsUnidentified() throws Exception {
        Path corrupt = Files.writeString(tempDir.resolve("corrupt-1.0.jar"), "not a zip file");
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {corrupt.toUri().toURL()}, null)) {
            DependencyInventory inventory = withClassPathInventory(patternResolver(Map.of(), loader), ".");

            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.of(1, 1, List.of("corrupt-1.0.jar")));
        }
    }

    @Test
    void anSbomAndClassesDirectoryAloneDoNotProveArchiveCoverage() throws Exception {
        try (URLClassLoader loader =
                explodedLoader("{\"components\":[{\"purl\":\"pkg:maven/com.example/resolved@1.0\"}]}", List.of())) {
            DependencyInventory inventory =
                    withClassPathInventory(new PathMatchingResourcePatternResolver(loader), ".");

            assertThat(inventory.dependencies()).hasSize(1);
            assertThat(inventory.coverage()).isEqualTo(DependencyCoverageDto.unavailable());
        }
        DependencyInventory noLoader = withClassPathInventory(
                sbomResolver("{\"components\":[{\"purl\":\"pkg:maven/com.example/resolved@1.0\"}]}"), "");
        assertThat(noLoader.dependencies()).hasSize(1);
        assertThat(noLoader.coverage()).isEqualTo(DependencyCoverageDto.unavailable());
    }

    @Test
    void attributesAnArchiveByTheDescriptorReadFromInsideItEvenWhenTheNameDoesNotMatch() throws Exception {
        // A shaded archive's file name need not match the coordinates of the descriptor inside it, so
        // attribution follows the descriptor's owning-archive location rather than guessing from the name.
        Path fatJar = repackagedJar("app.jar", "BOOT-INF/lib/", List.of("shaded-bundle-9.9.9.jar"));
        Resource descriptor = descriptorAtUrl(
                "jar:file:/app/app.jar!/BOOT-INF/lib/shaded-bundle-9.9.9.jar!/META-INF/maven/com.acme/widget/pom.properties");
        ResourcePatternResolver resolver =
                patternResolver(Map.of("classpath*:META-INF/maven/*/*/pom.properties", List.of(descriptor)));

        DependencyCoverageDto coverage =
                withClassPathInventory(resolver, fatJar.toString()).coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.COMPLETE);
        assertThat(coverage.archivesIdentified()).isEqualTo(1);
    }

    @Test
    void attributesAnArchiveFromABoot32NestedResourceUri() throws Exception {
        // Spring Boot 3.2+ addresses nested entries with jar:nested:, which is only URL-resolvable while
        // Boot's protocol handler is registered, so attribution must also work from the URI alone.
        Path fatJar = repackagedJar("app.jar", "BOOT-INF/lib/", List.of("shaded-bundle-9.9.9.jar"));
        Resource descriptor = descriptorAtUri(
                "jar:nested:/app/app.jar/!BOOT-INF/lib/shaded-bundle-9.9.9.jar!/META-INF/maven/com.acme/widget/pom.properties");
        ResourcePatternResolver resolver =
                patternResolver(Map.of("classpath*:META-INF/maven/*/*/pom.properties", List.of(descriptor)));

        DependencyCoverageDto coverage =
                withClassPathInventory(resolver, fatJar.toString()).coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.COMPLETE);
    }

    @Test
    void reportsUnavailableCoverageWhenTheArchivesCannotBeEnumerated() {
        DependencyCoverageDto coverage =
                withClassPathInventory(emptyResolver(), "").coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.UNAVAILABLE);
        assertThat(coverage.archivesFound()).isZero();
        assertThat(coverage.unidentifiedArchives()).isEmpty();
    }

    @Test
    void anUnreadableArchiveIsCountedRatherThanSilentlyDropped() throws Exception {
        Path corrupt = Files.writeString(tempDir.resolve("corrupt-1.0.0.jar"), "not a zip file");

        DependencyCoverageDto coverage =
                withClassPathInventory(emptyResolver(), corrupt.toString()).coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.INCOMPLETE);
        assertThat(coverage.unidentifiedArchives()).containsExactly("corrupt-1.0.0.jar");
    }

    @Test
    void boundsTheReportedArchiveNamesWhileKeepingTheCountExact() throws Exception {
        int total = DependencyCatalog.MAX_UNIDENTIFIED_ARCHIVES + 5;
        List<String> nested = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            nested.add("mystery-%04d-1.0.0.jar".formatted(i));
        }
        Path fatJar = repackagedJar("app.jar", "BOOT-INF/lib/", nested);

        DependencyCoverageDto coverage =
                withClassPathInventory(emptyResolver(), fatJar.toString()).coverage();

        assertThat(coverage.archivesUnidentified()).isEqualTo(total);
        assertThat(coverage.unidentifiedArchives()).hasSize(DependencyCatalog.MAX_UNIDENTIFIED_ARCHIVES);
        assertThat(coverage.unidentifiedArchivesTruncated()).isTrue();
    }

    // -----------------------------------------------------------------------------------------------
    // First-party module archives and Spring Boot packaging artifacts
    // -----------------------------------------------------------------------------------------------

    @Test
    void reportsExplodedModuleJarsAsFirstPartyAndIdentifiesJarmodeToolsFromItsManifest() throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("app/BOOT-INF/lib"));
        Path classes = Files.createDirectories(tempDir.resolve("app/BOOT-INF/classes/META-INF/sbom"));
        Files.writeString(
                classes.resolve("application.cdx.json"),
                "{\"components\":[{\"purl\":\"pkg:maven/com.example/resolved@1.0\"}]}");
        List<URL> urls = new ArrayList<>();
        urls.add(classes.getParent().getParent().toUri().toURL());
        urls.add(writeJar(lib.resolve("resolved-1.0.jar"), null, List.of("com/example/Resolved.class"))
                .toUri()
                .toURL());
        for (String module : List.of("cart", "order")) {
            urls.add(writeJar(
                            lib.resolve(module + ".jar"),
                            manifest(Map.of()),
                            List.of("com/boosting/" + module + "/", "com/boosting/" + module + "/Service.class"))
                    .toUri()
                    .toURL());
        }
        urls.add(writeJar(
                        lib.resolve("spring-boot-jarmode-tools-4.1.1.jar"),
                        jarmodeManifest("4.1.1"),
                        List.of("org/springframework/boot/jarmode/tools/ToolsJarMode.class"))
                .toUri()
                .toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            DependencyInventory inventory = withClassPathInventory(
                    new DependencyCatalog(
                            new PathMatchingResourcePatternResolver(loader), () -> List.of("com.boosting")),
                    ".");

            assertThat(inventory.dependencies())
                    .extracting(DependencyDto::packageName, DependencyDto::version, DependencyDto::source)
                    .containsExactly(
                            tuple("com.example:resolved", "1.0", "CycloneDX SBOM"),
                            tuple(
                                    "org.springframework.boot:spring-boot-jarmode-tools",
                                    "4.1.1",
                                    "Spring Boot manifest"));
            assertThat(inventory.coverage())
                    .isEqualTo(DependencyCoverageDto.of(4, 0, List.of(), 2, List.of("cart.jar", "order.jar")));
            assertThat(inventory.coverage().archivesIdentified()).isEqualTo(2);
        }
    }

    @Test
    void recognizesFirstPartyAndJarmodeArchivesNestedInARepackagedJar() throws Exception {
        Path fatJar = repackagedJarWithContents(Map.of(
                "users.jar",
                jarBytes(manifest(Map.of()), List.of("com/boosting/user/User.class")),
                "spring-boot-jarmode-tools-4.1.1.jar",
                jarBytes(jarmodeManifest("4.1.1"), List.of("org/springframework/boot/jarmode/tools/Tools.class")),
                "shaded-1.0.jar",
                jarBytes(null, List.of("com/boosting/Shaded.class", "org/relocated/Library.class"))));

        DependencyInventory inventory = withClassPathInventory(
                new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")), fatJar.toString());

        assertThat(inventory.dependencies())
                .extracting(DependencyDto::packageName)
                .containsExactly("org.springframework.boot:spring-boot-jarmode-tools");
        assertThat(inventory.coverage())
                .isEqualTo(DependencyCoverageDto.of(3, 1, List.of("shaded-1.0.jar"), 1, List.of("users.jar")));
    }

    @Test
    void keepsArchivesUnidentifiedWhenTheyAreNotProvablyFirstPartyOrBootArtifacts() throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("strict/BOOT-INF/lib"));
        Path shaded = writeJar(
                lib.resolve("module-with-shaded.jar"),
                null,
                List.of("com/boosting/Module.class", "com/google/common/Lists.class"));
        Path resources = writeJar(lib.resolve("assets.jar"), null, List.of("static/app.js"));
        Path sibling = writeJar(lib.resolve("sibling.jar"), null, List.of("com/boostingextra/Other.class"));
        Path wrongVersion = writeJar(
                lib.resolve("spring-boot-jarmode-tools-4.1.1.jar"),
                jarmodeManifest("4.1.0"),
                List.of("org/springframework/boot/jarmode/tools/Tools.class"));
        Path wrongTitle = writeJar(
                lib.resolve("spring-boot-jarmode-tools-4.2.0.jar"),
                manifest(Map.of("Implementation-Title", "Something Else", "Implementation-Version", "4.2.0")),
                List.of("org/example/Tools.class"));
        Path corrupt = Files.writeString(lib.resolve("broken.jar"), "not a zip file");
        String classPath = String.join(
                File.pathSeparator,
                shaded.toString(),
                resources.toString(),
                sibling.toString(),
                wrongVersion.toString(),
                wrongTitle.toString(),
                corrupt.toString());

        DependencyInventory inventory = withClassPathInventory(
                new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")), classPath);

        assertThat(inventory.dependencies()).isEmpty();
        assertThat(inventory.coverage())
                .isEqualTo(DependencyCoverageDto.of(
                        6,
                        6,
                        List.of(
                                "assets.jar",
                                "broken.jar",
                                "module-with-shaded.jar",
                                "sibling.jar",
                                "spring-boot-jarmode-tools-4.1.1.jar",
                                "spring-boot-jarmode-tools-4.2.0.jar")));
    }

    @Test
    void withoutBasePackagesModuleJarsStayUnidentified() throws Exception {
        Path module = writeJar(tempDir.resolve("cart.jar"), manifest(Map.of()), List.of("com/boosting/Cart.class"));

        assertThat(withClassPathInventory(new DependencyCatalog(emptyResolver(), List::of), module.toString())
                        .coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("cart.jar")));
        assertThat(withClassPathInventory(
                                new DependencyCatalog(emptyResolver(), () -> {
                                    throw new IllegalStateException("no context");
                                }),
                                module.toString())
                        .coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("cart.jar")));
    }

    @Test
    void boundsTheReportedFirstPartyNamesWhileKeepingTheCountExact() throws Exception {
        int total = DependencyCatalog.MAX_UNIDENTIFIED_ARCHIVES + 3;
        Map<String, byte[]> nested = new java.util.LinkedHashMap<>();
        byte[] module = jarBytes(null, List.of("com/boosting/Module.class"));
        for (int i = 0; i < total; i++) {
            nested.put("module-%04d.jar".formatted(i), module);
        }
        Path fatJar = repackagedJarWithContents(nested);

        DependencyCoverageDto coverage = withClassPathInventory(
                        new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")), fatJar.toString())
                .coverage();

        assertThat(coverage.status()).isEqualTo(DependencyCoverageDto.COMPLETE);
        assertThat(coverage.archivesFirstParty()).isEqualTo(total);
        assertThat(coverage.firstPartyArchives()).hasSize(DependencyCatalog.MAX_UNIDENTIFIED_ARCHIVES);
        assertThat(coverage.firstPartyArchivesTruncated()).isTrue();
    }

    /** As Spring Boot writes it: every library listed individually, the project module in {@code application}. */
    private static final String LAYERS_INDEX = """
            - "dependencies":
              - "BOOT-INF/lib/boosting-sdk.jar"
            - "spring-boot-loader":
              - "org/"
            - "snapshot-dependencies":
            - "application":
              - "BOOT-INF/classes/"
              - "BOOT-INF/classpath.idx"
              - "BOOT-INF/layers.idx"
              - "BOOT-INF/lib/users.jar"
              - "META-INF/"
            """;

    @Test
    void aLayersIndexKeepsDependencyLayerArchivesUnidentifiedEvenInTheApplicationNamespace() throws Exception {
        byte[] sdk = jarBytes(null, List.of("com/boosting/sdk/Client.class"));
        Path fatJar = repackagedJarWithContents(
                Map.of("users.jar", jarBytes(null, List.of("com/boosting/user/User.class")), "boosting-sdk.jar", sdk),
                LAYERS_INDEX,
                true);

        DependencyCoverageDto coverage = withClassPathInventory(
                        new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")), fatJar.toString())
                .coverage();

        assertThat(coverage)
                .isEqualTo(DependencyCoverageDto.of(2, 1, List.of("boosting-sdk.jar"), 1, List.of("users.jar")));
    }

    @Test
    void anExtractedLayersIndexKeepsDependencyLayerArchivesUnidentified() throws Exception {
        Path bootInf = Files.createDirectories(tempDir.resolve("extracted/BOOT-INF"));
        Files.writeString(bootInf.resolve("layers.idx"), LAYERS_INDEX);
        Path users = writeJar(bootInf.resolve("lib/users.jar"), null, List.of("com/boosting/user/User.class"));
        Path sdk = writeJar(bootInf.resolve("lib/boosting-sdk.jar"), null, List.of("com/boosting/sdk/Client.class"));

        DependencyCoverageDto coverage = withClassPathInventory(
                        new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")),
                        users.toString(),
                        sdk.toString())
                .coverage();

        assertThat(coverage)
                .isEqualTo(DependencyCoverageDto.of(2, 1, List.of("boosting-sdk.jar"), 1, List.of("users.jar")));
    }

    @Test
    void anInPlaceLayeredExtractionResolvesDependencyLayerArchivesThroughTheApplicationLayerIndex() throws Exception {
        // jarmode=tools extract --layers --launcher writes each layer to its own <layer>/BOOT-INF/ tree, and only
        // the application layer carries the index.
        Path root = tempDir.resolve("in-place");
        Path application = Files.createDirectories(root.resolve("application/BOOT-INF"));
        Files.writeString(application.resolve("layers.idx"), LAYERS_INDEX);
        Path users = writeJar(application.resolve("lib/users.jar"), null, List.of("com/boosting/user/User.class"));
        Path sdk = writeJar(
                root.resolve("dependencies/BOOT-INF/lib/boosting-sdk.jar"),
                null,
                List.of("com/boosting/sdk/Client.class"));

        DependencyCoverageDto coverage = withClassPathInventory(
                        new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")),
                        users.toString(),
                        sdk.toString())
                .coverage();

        assertThat(coverage)
                .isEqualTo(DependencyCoverageDto.of(2, 1, List.of("boosting-sdk.jar"), 1, List.of("users.jar")));
    }

    @Test
    void anApplicationLayerModuleMayLiveBesideTheLauncherPackage() throws Exception {
        // The launcher is in com.boosting.gateway; its sibling modules are in com.boosting.*. Only the archive the
        // index places in the application layer is widened to the parent package.
        Path bootInf = Files.createDirectories(tempDir.resolve("merged/BOOT-INF"));
        Files.writeString(bootInf.resolve("layers.idx"), LAYERS_INDEX);
        Path users = writeJar(bootInf.resolve("lib/users.jar"), null, List.of("com/boosting/user/User.class"));
        Path sdk = writeJar(bootInf.resolve("lib/boosting-sdk.jar"), null, List.of("com/boosting/sdk/Client.class"));
        Path loose = writeJar(tempDir.resolve("loose.jar"), null, List.of("com/boosting/loose/Loose.class"));

        DependencyCoverageDto coverage = withClassPathInventory(
                        new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting.gateway")),
                        users.toString(),
                        sdk.toString(),
                        loose.toString())
                .coverage();

        assertThat(coverage)
                .isEqualTo(DependencyCoverageDto.of(
                        3, 2, List.of("boosting-sdk.jar", "loose.jar"), 1, List.of("users.jar")));
    }

    @Test
    void anUnreadableLayersIndexPlacesNoArchiveInTheApplicationLayer() throws Exception {
        Path fatJar = repackagedJarWithContents(
                Map.of("users.jar", jarBytes(null, List.of("com/boosting/user/User.class"))),
                "this is not a layers index\n",
                true);

        assertThat(withClassPathInventory(
                                new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")),
                                fatJar.toString())
                        .coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("users.jar")));
    }

    @Test
    void aJarmodeToolsManifestWithoutTheToolsClassesIsNotIdentified() throws Exception {
        Path forged = writeJar(
                tempDir.resolve("spring-boot-jarmode-tools-4.1.1.jar"),
                jarmodeManifest("4.1.1"),
                List.of("org/example/Payload.class"));

        DependencyInventory inventory =
                withClassPathInventory(new DependencyCatalog(emptyResolver(), () -> List.of()), forged.toString());

        assertThat(inventory.coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("spring-boot-jarmode-tools-4.1.1.jar")));
        assertThat(inventory.dependencies()).isEmpty();
    }

    @Test
    void compressedNestedArchivesAndTooBroadBasePackagesAreNotInspectedForFirstParty() throws Exception {
        Path deflated = repackagedJarWithContents(
                Map.of("users.jar", jarBytes(null, List.of("com/boosting/user/User.class"))), null, false);
        assertThat(withClassPathInventory(
                                new DependencyCatalog(emptyResolver(), () -> List.of("com.boosting")),
                                deflated.toString())
                        .coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("users.jar")));

        Path vendor = writeJar(tempDir.resolve("vendor.jar"), null, List.of("com/vendor/Library.class"));
        assertThat(withClassPathInventory(
                                new DependencyCatalog(emptyResolver(), () -> List.of("com")), vendor.toString())
                        .coverage())
                .isEqualTo(DependencyCoverageDto.of(1, 1, List.of("vendor.jar")));
    }

    // -----------------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------------

    private URLClassLoader explodedLoader(String sbom, List<String> archives) throws IOException {
        Path root = tempDir.resolve("exploded app+layout");
        Path classes = Files.createDirectories(root.resolve("BOOT-INF/classes"));
        Path sbomFile = classes.resolve("META-INF/sbom/application.cdx.json");
        Files.createDirectories(sbomFile.getParent());
        Files.writeString(sbomFile, sbom);
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (String archive : archives) {
            urls.add(plainJar("exploded app+layout/BOOT-INF/lib/" + archive)
                    .toUri()
                    .toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), null);
    }

    private Path repackagedJar(String name, String libraryPrefix, List<String> nestedArchives) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Spring-Boot-Lib", libraryPrefix);
        Path jar = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("BOOT-INF/classes/com/example/App.class"));
            jarOut.closeEntry();
            for (String nested : nestedArchives) {
                jarOut.putNextEntry(new ZipEntry(libraryPrefix + nested));
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private static Manifest manifest(Map<String, String> attributes) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        return manifest;
    }

    private static Manifest jarmodeManifest(String version) {
        return manifest(Map.of(
                "Implementation-Title",
                "Spring Boot Jarmode Tools",
                "Implementation-Version",
                version,
                "Automatic-Module-Name",
                "spring.boot.jarmode.tools"));
    }

    private static byte[] jarBytes(Manifest manifest, List<String> entries) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jarOut =
                manifest == null ? new JarOutputStream(bytes) : new JarOutputStream(bytes, manifest)) {
            for (String entry : entries) {
                jarOut.putNextEntry(new ZipEntry(entry));
                jarOut.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Path writeJar(Path jar, Manifest manifest, List<String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        Files.write(jar, jarBytes(manifest, entries));
        return jar;
    }

    /** A repackaged JAR whose {@code BOOT-INF/lib/} entries are real, stored archives, as Boot writes them. */
    private Path repackagedJarWithContents(Map<String, byte[]> nestedArchives) throws IOException {
        return repackagedJarWithContents(nestedArchives, null, true);
    }

    private Path repackagedJarWithContents(Map<String, byte[]> nestedArchives, String layersIndex, boolean stored)
            throws IOException {
        Manifest manifest = manifest(Map.of("Spring-Boot-Lib", "BOOT-INF/lib/"));
        Path jar = tempDir.resolve("repackaged-app.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("BOOT-INF/classes/com/boosting/App.class"));
            jarOut.closeEntry();
            if (layersIndex != null) {
                jarOut.putNextEntry(new ZipEntry("BOOT-INF/layers.idx"));
                jarOut.write(layersIndex.getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
            for (Map.Entry<String, byte[]> nested : nestedArchives.entrySet()) {
                byte[] content = nested.getValue();
                ZipEntry entry = new ZipEntry("BOOT-INF/lib/" + nested.getKey());
                if (!stored) {
                    jarOut.putNextEntry(entry);
                    jarOut.write(content);
                    jarOut.closeEntry();
                    continue;
                }
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(content);
                entry.setCrc(crc.getValue());
                jarOut.putNextEntry(entry);
                jarOut.write(content);
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private Path plainJar(String name) throws IOException {
        Path jar = tempDir.resolve(name);
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new ZipEntry("com/example/Library.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    private List<DependencyDto> withClassPath(ResourcePatternResolver resolver, String... entries) {
        return withClassPathInventory(resolver, entries).dependencies();
    }

    private DependencyInventory withClassPathInventory(ResourcePatternResolver resolver, String... entries) {
        return withClassPathInventory(new DependencyCatalog(resolver), entries);
    }

    private DependencyInventory withClassPathInventory(DependencyCatalog catalog, String... entries) {
        String previousClassPath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", String.join(File.pathSeparator, entries));
            return catalog.inventory();
        } finally {
            if (previousClassPath == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", previousClassPath);
            }
        }
    }

    private static final byte[] WIDGET_DESCRIPTOR =
            "groupId=com.acme\nartifactId=widget\nversion=1.2.3\n".getBytes(StandardCharsets.UTF_8);

    private static Resource descriptorAtUrl(String url) {
        return new ByteArrayResource(WIDGET_DESCRIPTOR) {
            @Override
            public URL getURL() throws IOException {
                return URI.create(url).toURL();
            }
        };
    }

    private static Resource descriptorAtUri(String uri) {
        return new ByteArrayResource(WIDGET_DESCRIPTOR) {
            @Override
            public URI getURI() {
                return URI.create(uri);
            }
        };
    }

    private static Resource sbomResource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8), "application.cdx.json");
    }

    private static Resource failingResource(String description) {
        return new ByteArrayResource(new byte[0]) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("unreadable");
            }

            @Override
            public String getDescription() {
                return description;
            }
        };
    }

    private static ResourcePatternResolver sbomResolver(String json) {
        return patternResolver(Map.of("classpath*:META-INF/sbom/application.cdx.json", List.of(sbomResource(json))));
    }

    private static ResourcePatternResolver emptyResolver() {
        return patternResolver(Map.of());
    }

    /**
     * A resolver that answers per pattern. The catalogue now queries several patterns, so a resolver that
     * returned the same resources for every pattern would feed SBOM bytes to the Maven-descriptor reader and
     * vice versa, testing something the runtime never does.
     */
    private static ResourcePatternResolver patternResolver(Map<String, List<Resource>> resourcesByPattern) {
        return patternResolver(resourcesByPattern, null);
    }

    private static ResourcePatternResolver patternResolver(
            Map<String, List<Resource>> resourcesByPattern, ClassLoader classLoader) {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return resourcesByPattern
                        .getOrDefault(locationPattern, List.of())
                        .toArray(new Resource[0]);
            }

            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(new byte[0]);
            }

            @Override
            public ClassLoader getClassLoader() {
                return classLoader;
            }
        };
    }
}
