package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;

interface DatabaseAdvisorRule {

    DatabaseAdvisorRuleDefinition definition();

    DatabaseAdvisorRuleResultDto evaluate(DatabaseAdvisorContext context);

    /**
     * Whether a finding this rule no longer reports means it was fixed. {@code false} for a rule that reads a
     * sliding window of runtime evidence, where a finding can vanish because the window moved.
     */
    default boolean comparableAcrossScans() {
        return true;
    }
}
