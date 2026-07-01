package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;

interface SecurityRule {

    SecurityRuleDefinition definition();

    SecurityRuleResultDto evaluate(SecurityContext context);
}
