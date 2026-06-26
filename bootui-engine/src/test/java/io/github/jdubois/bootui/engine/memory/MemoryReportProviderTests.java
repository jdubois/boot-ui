package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.engine.memory.MemoryCalculator.JdkVersion;
import io.github.jdubois.bootui.spi.MemoryRuntimeConfig;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for {@link MemoryReportProvider}, the framework-neutral engine service shared by the
 * Live Memory and JVM Tuning panels.
 *
 * <p>Uses the package-private {@code MemoryCalculator(JdkVersion)} seam to pin the JDK version so that
 * JVM-option assertions are reproducible regardless of the host JDK, and a fake
 * {@link MemoryRuntimeConfig} to drive the virtual-threads / Kubernetes-health-probe inputs without a
 * framework. Live {@link java.lang.management.ManagementFactory} data is used for heap, non-heap and
 * pool readings — these are always non-negative in a running JVM, which is all these tests assert about
 * them. The pure formula assertions are covered by {@link MemoryCalculatorTests} and
 * {@link MemoryKubernetesSizerTests}.</p>
 */
class MemoryReportProviderTests {

    private static final JdkVersion JDK_25 = () -> 25;

    private static MemoryRuntimeConfig config(boolean virtualThreads, boolean healthProbes) {
        return new MemoryRuntimeConfig() {
            @Override
            public boolean virtualThreadsEnabled() {
                return virtualThreads;
            }

            @Override
            public boolean kubernetesHealthProbesEnabled() {
                return healthProbes;
            }
        };
    }

    private static MemoryReportProvider providerWithDefaults() {
        return new MemoryReportProvider(new MemoryCalculator(JDK_25));
    }

    private static MemoryReportProvider providerWithDisabledDetector(MemoryRuntimeConfig runtimeConfig) {
        return new MemoryReportProvider(
                new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), runtimeConfig);
    }

    @Test
    void buildsExpectedTopLevelShape() {
        LiveMemoryReport report = providerWithDefaults().buildReport(null, null, null, null, null);

        assertThat(report.heap().name()).isEqualTo("Heap");
        assertThat(report.nonHeap().name()).isEqualTo("Non-Heap");
        assertThat(report.pools()).isNotNull();
        assertThat(report.jvmInputArguments()).isNotNull();
        assertThat(report.suggestedJvmOptions()).isNotBlank();
        assertThat(report.calculation()).isNotNull();
        assertThat(report.calculation().virtualThreadsEnabled()).isFalse();
        assertThat(report.kubernetes()).isNotNull();
    }

    @Test
    void honorsTotalMemoryMbOverride() {
        LiveMemoryReport report = providerWithDefaults().buildReport(512L, null, null, null, null);

        assertThat(report.calculation().totalMemoryBytes()).isEqualTo(512L * 1024L * 1024L);
    }

    @Test
    void honorsThreadCountOverride() {
        LiveMemoryReport report = providerWithDefaults().buildReport(1024L, 50, null, null, null);

        assertThat(report.calculation().threadCount()).isEqualTo(50);
    }

    @Test
    void suggestedJvmOptionsContainsRequiredFlags() {
        LiveMemoryReport report = providerWithDefaults().buildReport(1024L, null, null, null, null);

        assertThat(report.suggestedJvmOptions())
                .contains("-Xmx")
                .contains("-Xms")
                .contains("-XX:MaxMetaspaceSize=")
                .contains("-XX:+UseCompactObjectHeaders");
    }

    @Test
    void reportsLiveContextValues() {
        LiveMemoryReport report = providerWithDefaults().buildReport(null, null, null, null, null);

        assertThat(report.calculation().liveThreadCount()).isGreaterThan(0);
        assertThat(report.calculation().liveLoadedClassCount()).isGreaterThan(0);
    }

    @Test
    void kubernetesRecommendationReportsGuaranteedResources() {
        LiveMemoryReport report =
                providerWithDisabledDetector(MemoryRuntimeConfig.DEFAULTS).buildReport(1024L, null, 10, null, null);

        long expectedBytes = 1024L * 1024L * 1024L;
        assertThat(report.kubernetes().requestMemoryBytes()).isEqualTo(expectedBytes);
        assertThat(report.kubernetes().limitMemoryBytes()).isEqualTo(expectedBytes);
        assertThat(report.kubernetes().qosClass()).isEqualTo("Guaranteed");
        assertThat(report.kubernetes().burstableEnabled()).isFalse();
        assertThat(report.kubernetes().actuatorProbesEnabled()).isTrue();
        assertThat(report.kubernetes().yaml())
                .contains("resources:")
                .contains("MaxRAMPercentage")
                .contains("startupProbe")
                .contains("readinessProbe")
                .doesNotContain("-Xmx")
                .doesNotContain("-Xms");
    }

    @Test
    void detectedVirtualThreadsChangeStackSizing() {
        LiveMemoryReport enabled =
                providerWithDisabledDetector(config(true, true)).buildReport(1024L, 250, null, null, null);
        assertThat(enabled.calculation().virtualThreadsEnabled()).isTrue();
        assertThat(enabled.calculation().stackBytesPerThread()).isEqualTo(512L * 1024L);

        LiveMemoryReport disabled =
                providerWithDisabledDetector(config(false, true)).buildReport(1024L, 250, null, null, null);
        assertThat(disabled.calculation().virtualThreadsEnabled()).isFalse();
        assertThat(disabled.calculation().stackBytesPerThread()).isEqualTo(1024L * 1024L);
    }

    @Test
    void kubernetesActuatorToggleUsesRuntimeConfigAndCanBeOverridden() {
        LiveMemoryReport fromConfig =
                providerWithDisabledDetector(config(false, false)).buildReport(1024L, null, null, null, null);
        assertThat(fromConfig.kubernetes().actuatorProbesEnabled()).isFalse();
        assertThat(fromConfig.kubernetes().yaml()).doesNotContain("startupProbe");

        LiveMemoryReport overridden =
                providerWithDisabledDetector(config(false, false)).buildReport(1024L, null, null, null, true);
        assertThat(overridden.kubernetes().actuatorProbesEnabled()).isTrue();
        assertThat(overridden.kubernetes().yaml()).contains("startupProbe");
    }

    @Test
    void kubernetesBurstableChangesRequestAndQos() {
        LiveMemoryReport report =
                providerWithDisabledDetector(MemoryRuntimeConfig.DEFAULTS).buildReport(4096L, null, null, true, null);

        assertThat(report.kubernetes().burstableEnabled()).isTrue();
        assertThat(report.kubernetes().qosClass()).isEqualTo("Burstable");
        assertThat(report.kubernetes().limitMemory()).isEqualTo("4096Mi");
        assertThat(report.kubernetes().requestMemory()).isNotEqualTo("4096Mi");
    }
}
