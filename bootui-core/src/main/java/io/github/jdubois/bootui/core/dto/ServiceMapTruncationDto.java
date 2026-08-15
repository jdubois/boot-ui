package io.github.jdubois.bootui.core.dto;

/**
 * How much of the assembled service map was withheld to keep the rendered graph bounded.
 *
 * <p>Truncation is always reported rather than applied silently: a high-cardinality outbound surface must
 * be visibly summarized, never quietly dropped.</p>
 *
 * @param truncated whether at least one dependency was withheld
 * @param dependencyLimit the hard cap on rendered dependencies
 * @param dependenciesShown how many dependencies the report carries
 * @param dependenciesOmitted how many dependencies were withheld by the cap
 * @param interactionLimit the hard cap on retained interactions carried per edge
 */
public record ServiceMapTruncationDto(
        boolean truncated, int dependencyLimit, int dependenciesShown, int dependenciesOmitted, int interactionLimit) {}
