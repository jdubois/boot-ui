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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
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
    // Fixtures
    // -----------------------------------------------------------------------------------------------

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

    private Path plainJar(String name) throws IOException {
        Path jar = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, new Manifest())) {
            jarOut.putNextEntry(new ZipEntry("com/example/Library.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    private List<DependencyDto> withClassPath(ResourcePatternResolver resolver, String... entries) {
        return withClassPathInventory(resolver, entries).dependencies();
    }

    private DependencyInventory withClassPathInventory(ResourcePatternResolver resolver, String... entries) {
        String previousClassPath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", String.join(File.pathSeparator, entries));
            return new DependencyCatalog(resolver).inventory();
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
                return DependencyCatalogTests.class.getClassLoader();
            }
        };
    }
}
