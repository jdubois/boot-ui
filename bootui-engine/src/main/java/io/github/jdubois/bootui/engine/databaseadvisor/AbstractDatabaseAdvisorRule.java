package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;

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

    DatabaseAdvisorRuleResultDto violation(DatabaseAdvisorContext context, DatabaseFindings details) {
        return details.isEmpty() ? pass() : DatabaseAdvisorRuleSupport.violation(context, definition, details);
    }

    void unknown(DatabaseAdvisorContext context, String reason) {
        context.unknown(definition.id(), reason);
    }

    DatabaseAdvisorRuleResultDto assessed(DatabaseAdvisorContext context, int eligible, DatabaseFindings details) {
        return eligible == 0 && details.isEmpty()
                ? skipped(DatabaseAdvisorRuleSupport.NO_TARGETS)
                : violation(context, details);
    }
}
