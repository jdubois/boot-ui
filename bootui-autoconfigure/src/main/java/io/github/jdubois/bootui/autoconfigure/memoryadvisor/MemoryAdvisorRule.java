package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto;

interface MemoryAdvisorRule {

    MemoryAdvisorRuleDefinition definition();

    MemoryAdvisorRuleResultDto evaluate(MemoryAdvisorContext context);
}
