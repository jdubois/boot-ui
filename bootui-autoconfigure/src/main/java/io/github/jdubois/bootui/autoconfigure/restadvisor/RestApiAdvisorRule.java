package io.github.jdubois.bootui.autoconfigure.restadvisor;

import io.github.jdubois.bootui.core.dto.RestApiAdvisorRuleResultDto;

/**
 * One curated REST API Advisor rule. Implementations describe themselves through a stable
 * {@link RestApiAdvisorRuleDefinition} and evaluate a single outcome against the derived handler
 * model exposed by the {@link RestApiAdvisorContext}.
 */
interface RestApiAdvisorRule {

    RestApiAdvisorRuleDefinition definition();

    RestApiAdvisorRuleResultDto evaluate(RestApiAdvisorContext context);
}
