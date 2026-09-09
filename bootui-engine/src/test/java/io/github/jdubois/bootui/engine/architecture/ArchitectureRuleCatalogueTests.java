package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.EvidenceMarkingGuard;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catalogue guard for the Architecture Advisor: every registered rule must record what it looked at, so the
 * panel score reflects the whole catalogue instead of silently dropping rules that forgot to mark evidence.
 */
class ArchitectureRuleCatalogueTests {

    private static final String EVIDENCE_TYPE =
            "io.github.jdubois.bootui.engine.architecture.ArchitectureContext$ArchitectureEvaluationEvidence";

    private static final Set<String> MARKING_METHODS = Set.of("observed", "markUsableIf", "markRequiredUnknown");

    /** Rules that legitimately never mark applicability, each with the reason it is application-wide. */
    private static final Set<String> APPLICATION_WIDE = Set.of();

    @Test
    void everyRegisteredRuleMarksTheEvidenceItReliesOn() {
        assertThat(EvidenceMarkingGuard.rulesThatNeverMarkEvidence(
                        ArchitectureRuleRegistry.activeRules(), EVIDENCE_TYPE, MARKING_METHODS, APPLICATION_WIDE))
                .as(
                        "Architecture rules that never reach %s; call ArchitectureRuleSupport.observed(...) so the rule "
                                + "contributes to the panel score, or add it to APPLICATION_WIDE with a reason",
                        EVIDENCE_TYPE)
                .isEmpty();
    }

    @Test
    void theApplicationWideAllowListStaysMinimal() {
        assertThat(EvidenceMarkingGuard.allowedRulesThatDoMarkEvidence(
                        ArchitectureRuleRegistry.activeRules(), EVIDENCE_TYPE, MARKING_METHODS, APPLICATION_WIDE))
                .as("these rules now mark evidence and no longer need an allow-list entry")
                .isEmpty();
    }

    @Test
    void theCatalogueRegistersEachRuleOnce() {
        assertThat(EvidenceMarkingGuard.duplicatedRules(ArchitectureRuleRegistry.activeRules()))
                .isEmpty();
    }
}
