package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.MemoryCalculator.JdkVersion;
import io.github.jdubois.bootui.core.BootUiDtos.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryCalculationDto;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MemoryKubernetesSizerTests {

    private static final long MB = 1024L * 1024L;
    private static final JdkVersion JDK_24 = () -> 24;

    private final MemoryCalculator calculator = new MemoryCalculator(JDK_24);

    @Test
    void recommendsGuaranteedQosByDefault() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        KubernetesMemoryRecommendationDto recommendation =
                MemoryKubernetesSizer.recommend(calculation, 256 * MB, 128 * MB, 8 * MB, true, 1024 * MB);

        assertThat(recommendation.requestMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.limitMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.requestMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.limitMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.qosClass()).isEqualTo("Guaranteed");
        assertThat(recommendation.confidence()).isEqualTo("High");
        assertThat(recommendation.detectedContainerLimitMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.yaml())
                .startsWith("resources:\n  requests:\n    memory: \"1024Mi\"\n  limits:\n    memory: \"1024Mi\"")
                .contains("JAVA_TOOL_OPTIONS");
    }

    @Test
    void burstableRequestUsesLiveSnapshotRatherThanFixedRegionCaps() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation =
                MemoryKubernetesSizer.recommend(calculation, 64 * MB, 64 * MB, 0, false, null);

        assertThat(recommendation.currentSnapshotBytes()).isEqualTo(138 * MB);
        assertThat(recommendation.burstableRequestMemoryBytes()).isEqualTo(256 * MB);
        assertThat(recommendation.burstableRequestMemory()).isEqualTo("256Mi");
    }

    @Test
    void reportsLowConfidenceAndNoYamlWhenCalculationIsInvalid() {
        MemoryCalculationDto calculation = calculator.calculate(256 * MB, 5_000, 100_000, 0, 10, 100_000);

        KubernetesMemoryRecommendationDto recommendation =
                MemoryKubernetesSizer.recommend(calculation, 64 * MB, 64 * MB, 0, false, null);

        assertThat(recommendation.confidence()).isEqualTo("Low");
        assertThat(recommendation.requestMemoryBytes()).isZero();
        assertThat(recommendation.yaml()).isEmpty();
        assertThat(recommendation.warnings()).contains(calculation.error());
    }

    @Test
    void warnsWhenHeadroomIsBelowKubernetesRecommendation() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 0, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation =
                MemoryKubernetesSizer.recommend(calculation, 64 * MB, 64 * MB, 0, true, null);

        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Headroom below 10%"));
    }

    @Test
    void parsesCgroupLimitsAndIgnoresUnlimitedValues() {
        assertThat(ContainerMemoryLimitDetector.parseLimit("1073741824")).isEqualTo(OptionalLong.of(1024 * MB));
        assertThat(ContainerMemoryLimitDetector.parseLimit(String.valueOf(128L * 1024 * MB)))
                .isEqualTo(OptionalLong.of(128L * 1024 * MB));
        assertThat(ContainerMemoryLimitDetector.parseLimit("max")).isEmpty();
        assertThat(ContainerMemoryLimitDetector.parseLimit(String.valueOf(Long.MAX_VALUE)))
                .isEmpty();
        assertThat(ContainerMemoryLimitDetector.parseLimit("not-a-number")).isEmpty();
    }
}
