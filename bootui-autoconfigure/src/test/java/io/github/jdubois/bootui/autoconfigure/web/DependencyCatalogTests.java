package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.BootUiDtos.DependencyDto;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

class DependencyCatalogTests {

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
}
