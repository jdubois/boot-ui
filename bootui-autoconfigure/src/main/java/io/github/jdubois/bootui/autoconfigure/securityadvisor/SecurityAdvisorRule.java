package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import io.github.jdubois.bootui.core.dto.SecurityAdvisorRuleResultDto;

interface SecurityAdvisorRule {

    SecurityAdvisorRuleDefinition definition();

    SecurityAdvisorRuleResultDto evaluate(SecurityAdvisorContext context);
}
