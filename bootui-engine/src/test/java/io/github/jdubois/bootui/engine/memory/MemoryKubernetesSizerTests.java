package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import io.github.jdubois.bootui.spi.HealthProbeManifest;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MemoryKubernetesSizerTests {

    private static final long MB = 1024L * 1024L;

    private final MemoryCalculator calculator = new MemoryCalculator();

    @Test
    void equalMemoryRequestAndLimitDoesNotClaimGuaranteedQos() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                256 * MB,
                128 * MB,
                8 * MB,
                true,
                1024 * MB,
                512 * MB,
                false,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.requestMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.limitMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.requestMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.limitMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.qosClass()).isEqualTo("Depends on CPU");
        assertThat(recommendation.burstableEnabled()).isFalse();
        assertThat(recommendation.healthProbesEnabled()).isTrue();
        assertThat(recommendation.confidence()).isEqualTo("Medium");
        assertThat(recommendation.detectedContainerLimitMemory()).isEqualTo("1024Mi");
        assertThat(recommendation.warnings())
                .anySatisfy(warning ->
                        assertThat(warning).contains("Guaranteed QoS").contains("CPU request and limit"));
        assertThat(recommendation.javaToolOptions())
                .contains("-XX:MaxRAMPercentage=", "-XX:MinRAMPercentage=", "-XX:InitialRAMPercentage=")
                .doesNotContain(
                        "-Xmx",
                        "-Xms",
                        "-XX:+UseContainerSupport",
                        "-XX:MaxDirectMemorySize=",
                        "-XX:+UseG1GC",
                        "-XX:+UseZGC");
        assertThat(recommendation.yaml())
                .startsWith("resources:\n  requests:\n    memory: \"1024Mi\"\n  limits:\n    memory: \"1024Mi\"")
                .contains(
                        "JAVA_TOOL_OPTIONS",
                        "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED",
                        "startupProbe",
                        "readinessProbe",
                        "    port: http\n",
                        "-XX:MinRAMPercentage=")
                .doesNotContain("-Xmx", "-Xms", "port: 8080");
    }

    @Test
    void burstableRequestUsesCgroupCurrentWhenAvailable() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                64 * MB,
                64 * MB,
                0,
                false,
                1024 * MB,
                200 * MB,
                true,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.currentSnapshotBytes()).isEqualTo(200 * MB);
        assertThat(recommendation.burstableRequestMemoryBytes()).isEqualTo(320 * MB);
        assertThat(recommendation.requestMemoryBytes()).isEqualTo(320 * MB);
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("cgroup memory.current"));
    }

    @Test
    void burstableRequestFallsBackToJvmPoolsWithoutTreatingStacksAsResident() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation, 64 * MB, 64 * MB, 0, false, null, null, true, true, HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.currentSnapshotBytes()).isEqualTo(128 * MB);
        assertThat(recommendation.burstableRequestMemoryBytes()).isEqualTo(192 * MB);
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("committed JVM pools"));
    }

    @Test
    void lowerMemoryRequestIsTruthfullyClassifiedAsBurstable() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                64 * MB,
                64 * MB,
                0,
                false,
                1024 * MB,
                200 * MB,
                true,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.qosClass()).isEqualTo("Burstable");
        assertThat(recommendation.yaml())
                .startsWith("resources:\n  requests:\n    memory: \"320Mi\"\n  limits:\n    memory: \"1024Mi\"");
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("prevents the Pod from receiving Guaranteed QoS"));
    }

    @Test
    void burstableModeKeepsTruthfulQosWhenTheSnapshotMarginReachesTheLimit() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                64 * MB,
                64 * MB,
                0,
                false,
                1024 * MB,
                1000 * MB,
                true,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.requestMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.qosClass()).isEqualTo("Depends on CPU");
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Burstable mode did not lower requests.memory"))
                .noneSatisfy(warning ->
                        assertThat(warning).contains("this container prevents the Pod from receiving Guaranteed QoS"));
    }

    @Test
    void burstableFallbackSaturatesOverflowingSnapshotCountersAtTheLimit() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 10, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                false,
                null,
                null,
                true,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.currentSnapshotBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(recommendation.burstableRequestMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.requestMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(recommendation.qosClass()).isEqualTo("Depends on CPU");
    }

    @Test
    void healthProbeToggleControlsTheSnippet() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                256 * MB,
                128 * MB,
                8 * MB,
                true,
                null,
                null,
                false,
                false,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.healthProbesEnabled()).isFalse();
        assertThat(recommendation.yaml())
                .doesNotContain(
                        "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", "startupProbe", "readinessProbe", "livenessProbe");
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Actuator probes are omitted"));
    }

    @Test
    void quarkusManifestUsesSmallRyeDefaultsAndANamedPort() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                256 * MB,
                128 * MB,
                8 * MB,
                true,
                1024 * MB,
                512 * MB,
                false,
                true,
                HealthProbeManifest.QUARKUS_SMALLRYE);

        assertThat(recommendation.yaml())
                .doesNotContain("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", "/actuator/health", "port: 8080")
                .contains(
                        "    path: /q/health/started\n",
                        "    path: /q/health/ready\n",
                        "    path: /q/health/live\n",
                        "    port: http\n",
                        "\nstartupProbe:\n")
                .doesNotContain("\n\nstartupProbe");
        assertThat(recommendation.warnings())
                .anySatisfy(warning ->
                        assertThat(warning).contains("default paths").contains("named container port \"http\""));
    }

    @Test
    void reportsLowConfidenceAndNoYamlWhenCalculationIsInvalid() {
        MemoryCalculationDto calculation = calculator.calculate(256 * MB, 5_000, 100_000, 0, 10, 100_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation, 64 * MB, 64 * MB, 0, false, null, null, false, true, HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.confidence()).isEqualTo("Low");
        assertThat(recommendation.requestMemoryBytes()).isZero();
        assertThat(recommendation.yaml()).isEmpty();
        assertThat(recommendation.warnings()).contains(calculation.error());
    }

    @Test
    void modelConfidenceStaysLowWithoutMatchingCgroupObservations() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        KubernetesMemoryRecommendationDto recommendation = recommend(
                calculation,
                256 * MB,
                128 * MB,
                8 * MB,
                true,
                null,
                null,
                false,
                true,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.confidence()).isEqualTo("Low");
        assertThat(recommendation.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("does not consume its output"));
    }

    @Test
    void heapPercentageFollowsTheRenderedModelInsteadOfAUniversalSeventyFivePercentCap() {
        MemoryCalculationDto calculation = calculator.calculate(8L * 1024 * MB, 1, 0, 0, 1, 0);

        double percentage = MemoryKubernetesSizer.heapPercentage(calculation);

        assertThat(percentage).isGreaterThan(75.0);
        assertThat(calculator.buildKubernetesJvmOptions(calculation, percentage, percentage))
                .contains("-XX:MaxRAMPercentage=" + percentage)
                .contains("-XX:MinRAMPercentage=" + percentage);
    }

    @Test
    void foldedYamlScalarPreservesQuotesAndBackslashesLiterally() {
        MemoryCalculationDto calculation = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);
        String options = "-Dpath=C:\\tmp -Dmessage=\"hello world\"";
        double percentage = MemoryKubernetesSizer.heapPercentage(calculation);

        KubernetesMemoryRecommendationDto recommendation = MemoryKubernetesSizer.recommend(
                calculation,
                256 * MB,
                128 * MB,
                8 * MB,
                false,
                null,
                null,
                percentage,
                percentage,
                options,
                false,
                false,
                HealthProbeManifest.SPRING_ACTUATOR);

        assertThat(recommendation.yaml())
                .contains("      " + options)
                .doesNotContain("C:\\\\tmp", "\\\"hello world\\\"");
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

    private KubernetesMemoryRecommendationDto recommend(
            MemoryCalculationDto calculation,
            long heapCommittedBytes,
            long nonHeapCommittedBytes,
            long directBufferMemoryUsedBytes,
            boolean nativeMemoryTrackingEnabled,
            Long detectedContainerLimitBytes,
            Long detectedContainerCurrentUsageBytes,
            boolean burstableEnabled,
            boolean healthProbesEnabled,
            HealthProbeManifest healthProbeManifest) {
        double maxRamPercentage = MemoryKubernetesSizer.heapPercentage(calculation);
        String javaToolOptions = calculator.buildKubernetesJvmOptions(calculation, maxRamPercentage, maxRamPercentage);
        return MemoryKubernetesSizer.recommend(
                calculation,
                heapCommittedBytes,
                nonHeapCommittedBytes,
                directBufferMemoryUsedBytes,
                nativeMemoryTrackingEnabled,
                detectedContainerLimitBytes,
                detectedContainerCurrentUsageBytes,
                maxRamPercentage,
                maxRamPercentage,
                javaToolOptions,
                burstableEnabled,
                healthProbesEnabled,
                healthProbeManifest);
    }
}
