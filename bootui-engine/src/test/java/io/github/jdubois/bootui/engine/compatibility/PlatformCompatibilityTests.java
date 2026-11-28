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
        assertThat(PlatformCompatibility.quarkus4("4.0.0")).isEmpty();
        assertThat(PlatformCompatibility.quarkus4("4.0.1")).isEmpty();
        assertThat(PlatformCompatibility.quarkus4("4.1.999-SNAPSHOT")).isEmpty();
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

    @Test
    void distinguishesQuarkus4WrongGenerationUnknownMetadataAndFutureMajor() {
        assertThat(PlatformCompatibility.quarkus4("3.40.1.2")).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("BOOTUI-COMPAT-020");
            assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.ERROR);
            assertThat(finding.action()).contains("bootui-quarkus", "do not install both");
        });
        assertThat(PlatformCompatibility.quarkus4(null)).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("BOOTUI-COMPAT-021");
            assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.INFO);
        });
        assertThat(PlatformCompatibility.quarkus4("5.0.0")).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("BOOTUI-COMPAT-022");
            assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.WARNING);
        });
    }

    @Test
    void rejectsMixedQuarkusAdaptersEvenWhenVersionMetadataIsUnknown() {
        for (String version : new String[] {"4.0.1", null}) {
            assertThat(PlatformCompatibility.quarkus4(version, true))
                    .singleElement()
                    .satisfies(finding -> {
                        assertThat(finding.id()).isEqualTo("BOOTUI-COMPAT-023");
                        assertThat(finding.severity()).isEqualTo(PlatformCompatibility.Severity.ERROR);
                        assertThat(finding.action()).contains("remove bootui-quarkus", "bootui-quarkus-4");
                    });
        }
    }
}
