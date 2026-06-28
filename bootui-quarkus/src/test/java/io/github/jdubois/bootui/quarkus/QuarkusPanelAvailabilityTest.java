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
        return new QuarkusPanelAvailability()
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
}
