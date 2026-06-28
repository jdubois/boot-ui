package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins how the Quarkus panel manifest distinguishes the three availability outcomes: panels that are lit up,
 * panels that are deliberately and permanently not applicable on Quarkus (GraalVM, CRaC), and panels that are
 * merely not ported yet. The manifest is a pure function of the shared {@link BootUiPanels} registry, so this
 * needs no Quarkus runtime.
 */
class QuarkusPanelAvailabilityTest {

    private Map<String, PanelDto> manifestById() {
        return manifestById(StubConfig.empty());
    }

    private Map<String, PanelDto> manifestById(StubConfig config) {
        return new QuarkusPanelAvailability(config)
                .manifest().panels().stream().collect(Collectors.toMap(PanelDto::id, Function.identity()));
    }

    @Test
    void graalVmAndCracAreDeliberatelyNotApplicableOnQuarkus() {
        Map<String, PanelDto> panels = manifestById();
        for (String id : new String[] {BootUiPanels.GRAALVM, BootUiPanels.CRAC}) {
            PanelDto panel = panels.get(id);
            assertThat(panel).as("panel %s is present in the manifest", id).isNotNull();
            assertThat(panel.available())
                    .as("panel %s is unavailable on Quarkus", id)
                    .isFalse();
            assertThat(panel.unavailableReason())
                    .as("panel %s carries a deliberate, panel-specific reason (not the generic one)", id)
                    .isNotNull()
                    .doesNotContain("Not yet available")
                    .containsIgnoringCase("Not applicable on Quarkus");
        }
    }

    @Test
    void notYetPortedPanelsKeepTheGenericReason() {
        // Beans has no Quarkus backing yet; it must keep the generic "not yet" reason so it stays clearly
        // distinct from the deliberately-not-applicable GraalVM/CRaC panels above.
        PanelDto beans = manifestById().get(BootUiPanels.BEANS);
        assertThat(beans.available()).isFalse();
        assertThat(beans.unavailableReason()).isEqualTo("Not yet available on Quarkus.");
    }

    @Test
    void availablePanelsCarryNoUnavailableReason() {
        PanelDto architecture = manifestById().get(BootUiPanels.ARCHITECTURE);
        assertThat(architecture.available())
                .as("Architecture is lit up on Quarkus")
                .isTrue();
        assertThat(architecture.unavailableReason()).isNull();
    }

    @Test
    void hibernateIsUnavailableWithACapabilityHintWhenHibernateOrmIsAbsent() {
        // Default: bootui.internal.hibernate-present is unset, so the deployment processor never saw the
        // HIBERNATE_ORM capability. The panel must surface an honest capability hint, NOT the generic reason.
        PanelDto hibernate = manifestById().get(BootUiPanels.HIBERNATE);
        assertThat(hibernate.available()).isFalse();
        assertThat(hibernate.unavailableReason())
                .isNotNull()
                .doesNotContain("Not yet available")
                .containsIgnoringCase("quarkus-hibernate-orm");
    }

    @Test
    void hibernateIsAvailableWhenTheBuildTimeFlagIsSet() {
        StubConfig withHibernate = new StubConfig(Map.of(QuarkusPanelAvailability.HIBERNATE_PRESENT_KEY, "true"));
        PanelDto hibernate = manifestById(withHibernate).get(BootUiPanels.HIBERNATE);
        assertThat(hibernate.available())
                .as("Hibernate is lit up when quarkus-hibernate-orm is present")
                .isTrue();
        assertThat(hibernate.unavailableReason()).isNull();
    }

    @Test
    void githubAvailabilityRoutesThroughTheRepositoryDetector() {
        // GitHub availability is dynamic (mirrors Spring's PanelsController.githubAvailable()): it is computed
        // from the shared GitHubRepositoryDetector, never the static "not yet" fallback. This module is itself
        // a checkout of github.com/jdubois/boot-ui, so the detector finds the repo and the panel lights up with
        // no unavailable reason — exactly as it does in CI and in a developer's own GitHub-hosted project.
        PanelDto github = manifestById().get(BootUiPanels.GITHUB);
        assertThat(github).as("GitHub panel is present in the manifest").isNotNull();
        assertThat(github.available())
                .as("GitHub lights up when the working directory is a GitHub checkout")
                .isTrue();
        assertThat(github.unavailableReason()).isNull();
    }
}
