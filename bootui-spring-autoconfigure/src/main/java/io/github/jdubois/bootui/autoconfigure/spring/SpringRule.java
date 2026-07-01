package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;

interface SpringRule {

    SpringRuleDefinition definition();

    SpringRuleResultDto evaluate(SpringContext context);
}
