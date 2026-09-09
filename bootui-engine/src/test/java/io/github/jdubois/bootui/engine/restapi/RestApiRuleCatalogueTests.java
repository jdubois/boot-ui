package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.EvidenceMarkingGuard;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catalogue guard for the RestApi Advisor: every registered rule must record what it looked at, so the
 * panel score reflects the whole catalogue instead of silently dropping rules that forgot to mark evidence.
 */
class RestApiRuleCatalogueTests {

    private static final String EVIDENCE_TYPE = "io.github.jdubois.bootui.engine.restapi.RestApiEvaluationEvidence";

    private static final Set<String> MARKING_METHODS =
            Set.of("markApplicable", "markApplicableIf", "markRequiredUnknown");

    /**
     * Rules that legitimately never mark evidence. Each entry is a retired heuristic that keeps its rule ID
     * so existing dismissals stay valid, but always returns SKIPPED without inspecting the model: it observes
     * nothing, so it must not claim coverage either. Anything else belongs in the guarded set.
     */
    private static final Set<String> APPLICATION_WIDE = Set.of(
            "MutatingItemMethodsTargetResourceRule",
            "FormatSuffixInPathRule",
            "ExceptionHandlersDoNotExposeStackTracesRule");

    @Test
    void everyRegisteredRuleMarksTheEvidenceItReliesOn() {
        assertThat(EvidenceMarkingGuard.rulesThatNeverMarkEvidence(
                        RestApiRuleRegistry.activeRules(), EVIDENCE_TYPE, MARKING_METHODS, APPLICATION_WIDE))
                .as(
                        "RestApi rules that never reach %s; call context.targets(...) so the rule "
                                + "contributes to the panel score, or add it to APPLICATION_WIDE with a reason",
                        EVIDENCE_TYPE)
                .isEmpty();
    }

    @Test
    void theApplicationWideAllowListStaysMinimal() {
        assertThat(EvidenceMarkingGuard.allowedRulesThatDoMarkEvidence(
                        RestApiRuleRegistry.activeRules(), EVIDENCE_TYPE, MARKING_METHODS, APPLICATION_WIDE))
                .as("these rules now mark evidence, so they are no longer retired and must leave the allow-list")
                .isEmpty();
    }

    @Test
    void theCatalogueRegistersEachRuleOnce() {
        assertThat(EvidenceMarkingGuard.duplicatedRules(RestApiRuleRegistry.activeRules()))
                .isEmpty();
    }
}
