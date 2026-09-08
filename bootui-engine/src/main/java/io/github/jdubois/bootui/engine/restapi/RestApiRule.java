package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleAssessment;

/**
 * One curated REST API Advisor rule. Implementations describe themselves through a stable
 * {@link RestApiRuleDefinition} and evaluate a single outcome against the derived handler
 * model exposed by the {@link RestApiContext}.
 */
interface RestApiRule {

    RestApiRuleDefinition definition();

    RestApiRuleResultDto evaluate(RestApiContext context);

    default AdvisorRuleAssessment<RestApiRuleResultDto> evaluateAssessment(RestApiContext context) {
        return RestApiRuleSupport.assessment(evaluate(context));
    }
}
