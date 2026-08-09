package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;

interface DatabaseAdvisorRule {

    DatabaseAdvisorRuleDefinition definition();

    DatabaseAdvisorRuleResultDto evaluate(DatabaseAdvisorContext context);
}
