package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.vulnerabilities.PackageUrls.MavenCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PackageUrlsTests {

    @Test
    void parsesAMavenPackageUrl() {
        MavenCoordinates coordinates = PackageUrls.mavenCoordinates("pkg:maven/org.springframework/spring-core@7.0.9");

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.groupId()).isEqualTo("org.springframework");
        assertThat(coordinates.artifactId()).isEqualTo("spring-core");
        assertThat(coordinates.version()).isEqualTo("7.0.9");
        assertThat(coordinates.packageName()).isEqualTo("org.springframework:spring-core");
    }

    @Test
    void ignoresQualifiersAndSubpath() {
        MavenCoordinates coordinates = PackageUrls.mavenCoordinates(
                "pkg:maven/org.postgresql/postgresql@42.7.13?type=jar&classifier=sources#some/sub/path");

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.artifactId()).isEqualTo("postgresql");
        assertThat(coordinates.version()).isEqualTo("42.7.13");
    }

    @Test
    void acceptsAVersionContainingAQualifierSuffix() {
        MavenCoordinates coordinates =
                PackageUrls.mavenCoordinates("pkg:maven/org.hibernate.orm/hibernate-core@7.4.5.Final");

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.groupId()).isEqualTo("org.hibernate.orm");
        assertThat(coordinates.version()).isEqualTo("7.4.5.Final");
    }

    @Test
    void treatsThePurlTypeAsCaseInsensitive() {
        assertThat(PackageUrls.mavenCoordinates("PKG:MAVEN/org.example/sample@1.0.0"))
                .isEqualTo(new MavenCoordinates("org.example", "sample", "1.0.0"));
    }

    @Test
    void decodesPercentEncodedSegments() {
        MavenCoordinates coordinates = PackageUrls.mavenCoordinates("pkg:maven/org.example/sample@1.0.0%2Bbuild.7");

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.version()).isEqualTo("1.0.0+build.7");
    }

    @Test
    void keepsRawTextWhenPercentEncodingIsMalformed() {
        MavenCoordinates coordinates = PackageUrls.mavenCoordinates("pkg:maven/org.example/sam%ZZple@1.0.0");

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.artifactId()).isEqualTo("sam%ZZple");
    }

    @Test
    void flattensAMultiSegmentNamespaceIntoAGroupId() {
        // Maven namespaces are single-segment in practice, but a producer may still slash-separate them.
        assertThat(PackageUrls.mavenCoordinates("pkg:maven/org/example/sample@1.0.0"))
                .isEqualTo(new MavenCoordinates("org.example", "sample", "1.0.0"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "pkg:npm/left-pad@1.3.0",
                "pkg:maven/org.example/sample",
                "pkg:maven/sample@1.0.0",
                "pkg:maven/org.example/@1.0.0",
                "pkg:maven//sample@1.0.0",
                "pkg:maven/org.example/sample@",
                "pkg:maven/",
                "not a purl"
            })
    void returnsNullForAnythingThatIsNotACompleteMavenPurl(String purl) {
        assertThat(PackageUrls.mavenCoordinates(purl)).isNull();
    }
}
