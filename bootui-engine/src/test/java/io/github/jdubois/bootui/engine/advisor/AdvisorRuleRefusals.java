package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

/** The detail-read contract every advisor shares: each result id is a known rule, and any other id is unknown. */
public final class AdvisorRuleRefusals {

    /** One detail read of an advisor, such as {@code scanner.ruleViolations(rule, scanId, 0, 1)}. */
    @FunctionalInterface
    public interface RuleViolations {
        Object read(String ruleId, String scanId);
    }

    private AdvisorRuleRefusals() {}

    /**
     * Asserts that every rule a scan reported answers with a page or, without retained findings, with {@link
     * AdvisorScanState#NO_FINDINGS_MESSAGE}, and that an id no result has answers with {@link
     * AdvisorScanState#UNKNOWN_RULE_MESSAGE}: an agent can tell a typo from a rule that passed.
     */
    public static void assertEveryResultIsAKnownRule(List<String> resultIds, String scanId, RuleViolations read) {
        assertThat(resultIds).as("the scan reported its evaluated rules").isNotEmpty();
        for (String ruleId : resultIds) {
            try {
                assertThat(read.read(ruleId, scanId)).isNotNull();
            } catch (AdvisorViolationException refusal) {
                assertThat(refusal.status()).isEqualTo(404);
                assertThat(refusal.getMessage())
                        .as("%s was evaluated, so it is not an unknown rule", ruleId)
                        .isEqualTo(AdvisorScanState.NO_FINDINGS_MESSAGE);
            }
        }
        assertThatThrownBy(() -> read.read("definitely-unknown", scanId))
                .isInstanceOfSatisfying(AdvisorViolationException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(404);
                    assertThat(refusal.getMessage()).isEqualTo(AdvisorScanState.UNKNOWN_RULE_MESSAGE);
                });
    }
}
