package io.github.jdubois.bootui.autoconfigure.architecture;

import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureRuleResultDto;

/**
 * One curated architecture rule. Implementations describe themselves through a stable
 * {@link ArchitectureRuleDefinition} and evaluate a single outcome against the imported classes.
 */
interface ArchitectureRule {

    ArchitectureRuleDefinition definition();

    ArchitectureRuleResultDto evaluate(ArchitectureContext context);
}
