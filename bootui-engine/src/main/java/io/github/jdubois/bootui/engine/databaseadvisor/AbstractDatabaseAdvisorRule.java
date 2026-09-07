package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.List;

abstract class AbstractDatabaseAdvisorRule implements DatabaseAdvisorRule {

    private final DatabaseAdvisorRuleDefinition definition;

    AbstractDatabaseAdvisorRule(DatabaseAdvisorRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final DatabaseAdvisorRuleDefinition definition() {
        return definition;
    }

    abstract DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context);

    @Override
    public final DatabaseAdvisorRuleResultDto evaluate(DatabaseAdvisorContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return DatabaseAdvisorRuleSupport.error(definition, "Rule could not be evaluated: " + reason);
        }
    }

    DatabaseAdvisorRuleResultDto pass() {
        return DatabaseAdvisorRuleSupport.pass(definition);
    }

    DatabaseAdvisorRuleResultDto skipped(String reason) {
        return DatabaseAdvisorRuleSupport.skipped(definition, reason);
    }

    DatabaseAdvisorRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : DatabaseAdvisorRuleSupport.violation(definition, details);
    }

    void unknown(DatabaseAdvisorContext context, String reason) {
        context.unknown(definition.id(), reason);
    }

    DatabaseAdvisorRuleResultDto assessed(DatabaseAdvisorContext context, int eligible, List<String> details) {
        return eligible == 0 && details.isEmpty()
                ? skipped("No applicable targets were available for this check.")
                : violation(details);
    }
}
