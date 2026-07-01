package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.QuarkusMemoryRuntimeConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code MemoryRuntimeConfig} mapping the Quarkus adapter supplies to the shared engine
 * {@code MemoryReportProvider} behind the Live Memory panel.
 *
 * <p>Both answers are deliberate per-framework decisions rather than reads of a Spring-style property, so
 * they are asserted directly: virtual threads are reported off (Quarkus has no application-wide switch),
 * and Kubernetes health probes track SmallRye Health extension presence — which is absent from this
 * integration-test classpath, so the honest answer here is {@code false}.</p>
 */
@QuarkusTest
class BootUiQuarkusMemoryRuntimeConfigTest {

    @Inject
    QuarkusMemoryRuntimeConfig runtimeConfig;

    @Test
    void virtualThreadsAreReportedOff() {
        assertThat(runtimeConfig.virtualThreadsEnabled())
                .as("Quarkus has no application-wide virtual-threads switch; the report assumes platform threads")
                .isFalse();
    }

    @Test
    void healthProbesTrackSmallRyeHealthPresence() {
        assertThat(runtimeConfig.kubernetesHealthProbesEnabled())
                .as("SmallRye Health is not on the integration-test classpath, so probes are reported absent")
                .isFalse();
    }
}
