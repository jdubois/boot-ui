package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The detail-read contract every advisor shares: each rule of its catalogue is known, whether it found something,
 * passed, or was skipped, and any other id is unknown.
 */
public final class AdvisorRuleRefusals {

    /** One detail read of an advisor, such as {@code scanner.ruleViolations(rule, scanId, 0, 1)}. */
    @FunctionalInterface
    public interface RuleViolations {
        Object read(String ruleId, String scanId);
    }

    private AdvisorRuleRefusals() {}

    /**
     * Asserts that every violating rule a scan reported belongs to the advisor's catalogue and answers with a page, that
     * at least one catalogue rule without findings (a passed or skipped rule, absent from the report's results)
     * answers {@link AdvisorScanState#NO_FINDINGS_MESSAGE}, and that an id outside the catalogue answers {@link
     * AdvisorScanState#UNKNOWN_RULE_MESSAGE}: an agent can tell a typo from a rule that passed.
     *
     * @param catalog the ids of every rule the scanner ran
     * @param resultIds the ids of the report's results, which list only violating rules
     */
    public static void assertKnownAndUnknownRulesAreToldApart(
            Collection<String> catalog, List<String> resultIds, String scanId, RuleViolations read) {
        assertThat(catalog).as("the advisor's rule catalogue").isNotEmpty().doesNotHaveDuplicates();
        assertThat(catalog).as("every reported rule belongs to the catalogue").containsAll(resultIds);
        Set<String> withoutFindings = new LinkedHashSet<>(catalog);
        withoutFindings.removeAll(resultIds);
        assertThat(withoutFindings)
                .as("the fixture must leave a rule without findings, or the passed-rule contract goes unchecked")
                .isNotEmpty();
        for (String ruleId : resultIds) {
            try {
                assertThat(read.read(ruleId, scanId)).isNotNull();
            } catch (AdvisorViolationException refusal) {
                assertThat(refusal.getMessage())
                        .as("%s was reported, so it is not an unknown rule", ruleId)
                        .isEqualTo(AdvisorScanState.NO_FINDINGS_MESSAGE);
            }
        }
        for (String ruleId : withoutFindings) {
            assertThatThrownBy(() -> read.read(ruleId, scanId))
                    .as("%s ran without findings", ruleId)
                    .isInstanceOfSatisfying(AdvisorViolationException.class, refusal -> {
                        assertThat(refusal.status()).isEqualTo(404);
                        assertThat(refusal.getMessage()).isEqualTo(AdvisorScanState.NO_FINDINGS_MESSAGE);
                    });
        }
        assertThatThrownBy(() -> read.read("definitely-unknown", scanId))
                .isInstanceOfSatisfying(AdvisorViolationException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(404);
                    assertThat(refusal.getMessage()).isEqualTo(AdvisorScanState.UNKNOWN_RULE_MESSAGE);
                });
    }
}
