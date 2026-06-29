package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;

interface MemoryRule {

    MemoryRuleDefinition definition();

    MemoryRuleResultDto evaluate(MemoryContext context);
}
