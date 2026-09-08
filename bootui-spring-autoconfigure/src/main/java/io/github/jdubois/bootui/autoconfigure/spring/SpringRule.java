package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleAssessment;

interface SpringRule {

    SpringRuleDefinition definition();

    SpringRuleResultDto evaluate(SpringContext context);

    default AdvisorRuleAssessment<SpringRuleResultDto> evaluateAssessment(SpringContext context) {
        return SpringRuleSupport.assessment(evaluate(context));
    }
}
