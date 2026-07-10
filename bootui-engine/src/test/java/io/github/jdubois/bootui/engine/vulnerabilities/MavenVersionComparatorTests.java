package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.junit.jupiter.api.Test;

class MavenVersionComparatorTests {

    @Test
    void followsMavenComparableVersionQualifierOrdering() {
        List<String> ordered =
                List.of("1.0-alpha1", "1.0-beta1", "1.0-milestone1", "1.0-rc1", "1.0-SNAPSHOT", "1.0", "1.0-sp1");

        for (int i = 0; i < ordered.size() - 1; i++) {
            assertThat(MavenVersionComparator.compare(ordered.get(i), ordered.get(i + 1)))
                    .isNegative();
        }
    }

    @Test
    void recognizesMavenQualifierAliasesAndReleaseForms() {
        assertThat(MavenVersionComparator.compare("1.0-a1", "1.0-alpha1")).isZero();
        assertThat(MavenVersionComparator.compare("1.0-b1", "1.0-beta1")).isZero();
        assertThat(MavenVersionComparator.compare("1.0-m1", "1.0-milestone1")).isZero();
        assertThat(MavenVersionComparator.compare("1.0-cr1", "1.0-rc1")).isZero();
        assertThat(MavenVersionComparator.compare("1.0-ga", "1.0")).isZero();
        assertThat(MavenVersionComparator.compare("1.0-final", "1.0-release")).isZero();
    }

    @Test
    void comparesNumericSegmentsWithoutLexicalOrderingBugs() {
        assertThat(MavenVersionComparator.compare("1.9", "1.10")).isNegative();
        assertThat(MavenVersionComparator.compare("1.0", "1.0.0")).isZero();
        assertThat(MavenVersionComparator.compare("1.0.1", "1.1")).isNegative();
    }

    @Test
    void ordersUnknownQualifiersAfterKnownQualifiers() {
        assertThat(MavenVersionComparator.compare("1.0-sp", "1.0-vendor")).isNegative();
        assertThat(MavenVersionComparator.compare("1.0-vendor1", "1.0-vendor2")).isNegative();
    }

    @Test
    void matchesMavenComparableVersionAcrossRepresentativeForms() {
        List<String> versions = List.of(
                "1",
                "1.0",
                "1.0.0",
                "1.0-0",
                "1.0-1",
                "1.0.1",
                "1-1",
                "1alpha",
                "1-0alpha",
                "1.0alpha1",
                "1.0.alpha1",
                "1-alpha-beta",
                "1.0-alpha",
                "1.0-alpha1",
                "1.0-a1",
                "1.0-beta2",
                "1.0-b2",
                "1.0-milestone1",
                "1.0-m1",
                "1.0-rc1",
                "1.0-cr1",
                "1.0-SNAPSHOT",
                "1.0",
                "1.0-ga",
                "1.0-final",
                "1.0-release",
                "1.0-sp",
                "1.0-sp1",
                "1.0-vendor",
                "1.0-vendor1",
                "1.0-20240101.120000-1",
                "2.0");

        for (String left : versions) {
            for (String right : versions) {
                int expected = Integer.signum(new ComparableVersion(left).compareTo(new ComparableVersion(right)));
                assertThat(Integer.signum(MavenVersionComparator.compare(left, right)))
                        .as("%s compared with %s", left, right)
                        .isEqualTo(expected);
            }
        }
    }
}
