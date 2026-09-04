package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveNamesTests {

    @Test
    void extractsTheFileNameFromAnArchiveEntry() {
        assertThat(ArchiveNames.jarFileName("BOOT-INF/lib/spring-core-7.0.9.jar"))
                .isEqualTo("spring-core-7.0.9.jar");
    }

    @Test
    void extractsTheFileNameFromAWindowsClasspathEntry() {
        assertThat(ArchiveNames.jarFileName("C:\\repo\\org\\example\\sample-1.0.0.jar"))
                .isEqualTo("sample-1.0.0.jar");
    }

    @Test
    void acceptsABareFileName() {
        assertThat(ArchiveNames.jarFileName("sample-1.0.0.jar")).isEqualTo("sample-1.0.0.jar");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"BOOT-INF/classes/", "/path/to/classes", "app.war", ".jar", "/path/to/.jar"})
    void returnsNullWhenThePathDoesNotNameAJar(String path) {
        assertThat(ArchiveNames.jarFileName(path)).isNull();
    }

    @Test
    void matchesTheMainArtifactArchive() {
        assertThat(ArchiveNames.matches("spring-core-7.0.9.jar", "spring-core", "7.0.9"))
                .isTrue();
    }

    @Test
    void matchesAClassifiedArchive() {
        assertThat(ArchiveNames.matches(
                        "netty-transport-native-epoll-4.2.7-linux-x86_64.jar", "netty-transport-native-epoll", "4.2.7"))
                .isTrue();
    }

    @Test
    void doesNotMatchADifferentVersionOfTheSameArtifact() {
        assertThat(ArchiveNames.matches("spring-core-7.0.9.jar", "spring-core", "7.0.10"))
                .isFalse();
    }

    @Test
    void doesNotMatchAnArtifactWhoseNameIsAPrefixOfTheArchive() {
        // spring-core-7.0.9.jar must not be attributed to the "spring" artifact.
        assertThat(ArchiveNames.matches("spring-core-7.0.9.jar", "spring", "7.0.9"))
                .isFalse();
    }

    @Test
    void requiresANonEmptyClassifier() {
        assertThat(ArchiveNames.matches("sample-1.0.0-.jar", "sample", "1.0.0")).isFalse();
    }

    @Test
    void rejectsBlankOrMissingCoordinates() {
        assertThat(ArchiveNames.matches("sample-1.0.0.jar", null, "1.0.0")).isFalse();
        assertThat(ArchiveNames.matches("sample-1.0.0.jar", "sample", null)).isFalse();
        assertThat(ArchiveNames.matches(null, "sample", "1.0.0")).isFalse();
        assertThat(ArchiveNames.matches("sample-1.0.0.jar", " ", "1.0.0")).isFalse();
        assertThat(ArchiveNames.matches("sample-1.0.0.jar", "sample", " ")).isFalse();
    }
}
