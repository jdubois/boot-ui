package io.github.jdubois.bootui.engine.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Behaviour tests for the framework-neutral {@link HealthService}, driven by a fake
 * {@link io.github.jdubois.bootui.spi.HealthProvider} and a test {@link HealthGuidance}. These pin the
 * DISABLED root, the "only default contributors" guidance, the default-failure pass-through, and the
 * non-default pass-through independently of any framework.
 */
class HealthServiceTests {

    private static final HealthGuidance GUIDANCE = new HealthGuidance(
            Set.of("diskSpace", "livenessState", "readinessState", "ping", "ssl"),
            "backend unavailable",
            List.of(new HealthSetupStepDto("Add a backend", "do it", List.of("dep:health"))),
            "only defaults present",
            List.of(new HealthSetupStepDto("Add contributors", "do it", List.of("class MyIndicator"))));

    private static HealthService service(HealthNodeDto root) {
        return new HealthService(() -> root, GUIDANCE);
    }

    @Test
    void rendersDisabledRootWhenProviderIsNull() {
        HealthNodeDto node = new HealthService(null, GUIDANCE).health();

        assertThat(node.name()).isEqualTo("application");
        assertThat(node.status()).isEqualTo("DISABLED");
        assertThat(node.available()).isFalse();
        assertThat(node.unavailableReason()).isEqualTo("backend unavailable");
        assertThat(node.guidanceReason()).isNull();
        assertThat(node.components()).isEmpty();
        assertThat(node.setup()).hasSize(1);
        assertThat(node.setup().get(0).title()).isEqualTo("Add a backend");
    }

    @Test
    void rendersDisabledRootWhenProviderReturnsNull() {
        HealthNodeDto node = service(null).health();

        assertThat(node.status()).isEqualTo("DISABLED");
        assertThat(node.available()).isFalse();
        assertThat(node.unavailableReason()).isEqualTo("backend unavailable");
    }

    @Test
    void addsGuidanceWhenOnlyDefaultContributorsArePresent() {
        HealthNodeDto root = new HealthNodeDto(
                "application",
                "UP",
                null,
                List.of(
                        new HealthNodeDto("livenessState", "UP", null, List.of()),
                        new HealthNodeDto("readinessState", "UP", null, List.of()),
                        new HealthNodeDto("ssl", "UP", null, List.of())));

        HealthNodeDto node = service(root).health();

        assertThat(node.status()).isEqualTo("UP");
        assertThat(node.available()).isTrue();
        assertThat(node.unavailableReason()).isNull();
        assertThat(node.guidanceReason()).isEqualTo("only defaults present");
        assertThat(node.setup()).hasSize(1);
        assertThat(node.setup().get(0).title()).isEqualTo("Add contributors");
        assertThat(node.components()).hasSize(3);
    }

    @Test
    void keepsDefaultContributorFailuresVisibleWithoutGuidance() {
        HealthNodeDto root = new HealthNodeDto(
                "application",
                "DOWN",
                null,
                List.of(new HealthNodeDto("diskSpace", "DOWN", java.util.Map.of("error", "low disk"), List.of())));

        HealthNodeDto node = service(root).health();

        assertThat(node.status()).isEqualTo("DOWN");
        assertThat(node.available()).isTrue();
        assertThat(node.guidanceReason()).isNull();
        assertThat(node.setup()).isEmpty();
    }

    @Test
    void passesThroughWhenANonDefaultContributorIsPresent() {
        HealthNodeDto root = new HealthNodeDto(
                "application",
                "UP",
                null,
                List.of(
                        new HealthNodeDto("db", "UP", null, List.of()),
                        new HealthNodeDto("diskSpace", "UP", null, List.of())));

        HealthNodeDto node = service(root).health();

        assertThat(node.guidanceReason()).isNull();
        assertThat(node.available()).isTrue();
        assertThat(node.components()).hasSize(2);
    }

    @Test
    void passesThroughWhenRootHasNoComponents() {
        HealthNodeDto root = new HealthNodeDto("application", "UNKNOWN", null, List.of());

        HealthNodeDto node = service(root).health();

        assertThat(node.status()).isEqualTo("UNKNOWN");
        assertThat(node.guidanceReason()).isNull();
        assertThat(node.available()).isTrue();
    }

    @Test
    void detectsDefaultsNestedUnderACompositeChild() {
        HealthNodeDto root = new HealthNodeDto(
                "application",
                "UP",
                null,
                List.of(new HealthNodeDto(
                        "livenessState", "UP", null, List.of(new HealthNodeDto("ping", "UP", null, List.of())))));

        HealthNodeDto node = service(root).health();

        assertThat(node.guidanceReason()).isEqualTo("only defaults present");
    }
}
