package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

class DependencyCatalogTests {

    @TempDir
    Path tempDir;

    private static ResourcePatternResolver emptyResolver() {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return new Resource[0];
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

    @Test
    void discoversMavenCoordinatesFromJavaClassPathJarsWithoutPomProperties() {
        String previousClassPath = System.getProperty("java.class.path");
        try {
            System.setProperty(
                    "java.class.path",
                    String.join(
                            File.pathSeparator,
                            "/home/user/.m2/repository/org/apache/tomcat/embed/tomcat-embed-core/11.0.21/tomcat-embed-core-11.0.21.jar",
                            "/home/user/.m2/repository/org/postgresql/postgresql/42.7.10/postgresql-42.7.10.jar"));

            List<DependencyDto> dependencies = new DependencyCatalog(emptyResolver()).dependencies();

            assertThat(dependencies)
                    .extracting(DependencyDto::packageName, DependencyDto::version)
                    .contains(
                            org.assertj.core.api.Assertions.tuple(
                                    "org.apache.tomcat.embed:tomcat-embed-core", "11.0.21"),
                            org.assertj.core.api.Assertions.tuple("org.postgresql:postgresql", "42.7.10"));
        } finally {
            if (previousClassPath == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", previousClassPath);
            }
        }
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

        List<DependencyDto> dependencies = withClassPath(jar.toString());

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version, DependencyDto::source)
                .containsExactly(org.assertj.core.api.Assertions.tuple(
                        "com.acme.libs:acme-widget", "2.1.0", "Adjacent Maven POM"));
    }

    @Test
    void doesNotInventAGroupIdForANonstandardPathWithoutMavenMetadata() throws Exception {
        Path versionDirectory = tempDir.resolve("custom-cache/acme-widget/2.1.0");
        Files.createDirectories(versionDirectory);
        Path jar = Files.createFile(versionDirectory.resolve("acme-widget-2.1.0.jar"));

        assertThat(withClassPath(jar.toString())).isEmpty();
    }

    @Test
    void skipsUnreadableMavenMetadataWithoutDiscardingReadableEntries() {
        Resource readable = new ByteArrayResource(
                "groupId=com.acme\nartifactId=widget\nversion=1.2.3\n".getBytes(StandardCharsets.UTF_8));
        Resource unreadable = new ByteArrayResource(new byte[0]) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("unreadable");
            }

            @Override
            public String getDescription() {
                return "unreadable pom.properties";
            }
        };
        ResourcePatternResolver resolver = resolver(readable, unreadable);

        List<DependencyDto> dependencies = new DependencyCatalog(resolver).dependencies();

        assertThat(dependencies)
                .extracting(DependencyDto::packageName, DependencyDto::version)
                .contains(org.assertj.core.api.Assertions.tuple("com.acme:widget", "1.2.3"));
    }

    @Test
    void rejectsJarNamesWhoseVersionOnlySharesAStringPrefix() throws Exception {
        Path versionDirectory = tempDir.resolve("repository/org/example/widget/1.0");
        Files.createDirectories(versionDirectory);
        Path wrongVersionJar = Files.createFile(versionDirectory.resolve("widget-1.0.1.jar"));

        assertThat(withClassPath(wrongVersionJar.toString())).isEmpty();
    }

    private List<DependencyDto> withClassPath(String classPath) {
        String previousClassPath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", classPath);
            return new DependencyCatalog(emptyResolver()).dependencies();
        } finally {
            if (previousClassPath == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", previousClassPath);
            }
        }
    }

    private static ResourcePatternResolver resolver(Resource... resources) {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return resources;
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
