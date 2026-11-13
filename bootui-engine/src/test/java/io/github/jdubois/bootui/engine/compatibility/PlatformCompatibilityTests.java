package io.github.jdubois.bootui.engine.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformCompatibilityTests {

    @Test
    void acceptsSupportedLinesRatherThanOnlyTheBuildPatch() {
        assertThat(PlatformCompatibility.spring("4.0.0", "7.0.0", true)).isEmpty();
        assertThat(PlatformCompatibility.spring("4.1.999-SNAPSHOT", "7.1.0", true))
                .isEmpty();
        assertThat(PlatformCompatibility.quarkus("3.33.4.redhat-00001")).isEmpty();
        assertThat(PlatformCompatibility.quarkus("3.40.1.1")).isEmpty();
    }

    @Test
    void rejectsProvenIncompatiblePlatformGenerationsAndApis() {
        assertThat(PlatformCompatibility.spring("3.5.0", "6.2.0", false))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.ERROR));
        assertThat(PlatformCompatibility.spring("4.1.2", "7.0.10", false))
                .singleElement()
                .satisfies(finding -> assertThat(finding.id()).isEqualTo("BOOTUI-COMPAT-001"));
        assertThat(PlatformCompatibility.quarkus("3.9.9"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.ERROR));
    }

    @Test
    void distinguishesUnknownMetadataFromFutureGenerations() {
        assertThat(PlatformCompatibility.spring("unknown", null, true))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.INFO));
        assertThat(PlatformCompatibility.quarkus("999999999999.0"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.INFO));
        assertThat(PlatformCompatibility.quarkus("4.0.0"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.WARNING));
    }
}
