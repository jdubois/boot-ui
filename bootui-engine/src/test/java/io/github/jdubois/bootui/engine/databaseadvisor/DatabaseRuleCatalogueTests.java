package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.EvidenceMarkingGuard;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Catalogue guard for the Database Advisor: every registered rule reports its findings through {@link DatabaseFindings}. */
class DatabaseRuleCatalogueTests {

    @Test
    void everyRegisteredRuleRecordsItsFindingsWithAnIdentity() {
        assertThat(EvidenceMarkingGuard.rulesThatNeverMarkEvidence(
                        DatabaseAdvisorRuleRegistry.activeRules(),
                        "io.github.jdubois.bootui.engine.databaseadvisor.DatabaseFindings",
                        Set.of("add"),
                        Set.of()))
                .as("Database rules that never record an identity-carrying finding")
                .isEmpty();
    }
}
