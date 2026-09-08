package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleAssessment;

interface MemoryRule {

    MemoryRuleDefinition definition();

    MemoryRuleResultDto evaluate(MemoryContext context);

    default AdvisorRuleAssessment<MemoryRuleResultDto> evaluateAssessment(MemoryContext context) {
        return MemoryRuleSupport.assessment(evaluate(context));
    }
}
