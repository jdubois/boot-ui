package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusServerPortSupplier}'s launch-mode-aware port-key selection — the only
 * branching in the supplier. The end-to-end resolution (reading the live test port and reaching the
 * running app) is proven by {@code BootUiQuarkusHttpProbeResourceTest} under {@code @QuarkusTest}; here we
 * pin the pure mapping so the {@code DEVELOPMENT}/{@code NORMAL} branch (not exercised by the test-mode IT)
 * is also covered.
 */
class QuarkusServerPortSupplierTest {

    @Test
    void testModeResolvesTheQuarkusTestPort() {
        assertThat(QuarkusServerPortSupplier.portKey(LaunchMode.TEST))
                .as("under @QuarkusTest the server binds to quarkus.http.test-port, not quarkus.http.port")
                .isEqualTo("quarkus.http.test-port");
        assertThat(QuarkusServerPortSupplier.defaultPort(LaunchMode.TEST))
                .as("Quarkus' default test port")
                .isEqualTo(8081);
    }

    @Test
    void developmentAndNormalResolveTheMainHttpPort() {
        for (LaunchMode mode : new LaunchMode[] {LaunchMode.DEVELOPMENT, LaunchMode.NORMAL}) {
            assertThat(QuarkusServerPortSupplier.portKey(mode))
                    .as("non-test modes serve on quarkus.http.port")
                    .isEqualTo("quarkus.http.port");
            assertThat(QuarkusServerPortSupplier.defaultPort(mode))
                    .as("Quarkus' default HTTP port")
                    .isEqualTo(8080);
        }
    }
}
