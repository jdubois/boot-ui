package io.github.jdubois.bootui.autoconfigure.springadvisor;

import io.github.jdubois.bootui.core.dto.SpringAdvisorRuleResultDto;

interface SpringAdvisorRule {

    SpringAdvisorRuleDefinition definition();

    SpringAdvisorRuleResultDto evaluate(SpringAdvisorContext context);
}
