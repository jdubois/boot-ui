package io.github.jdubois.bootui.core.dto;

/**
 * How one rule's findings moved since the previous scan, so a client can tell which rules have something to look at
 * without paging every rule.
 *
 * @param ruleId the rule
 * @param newCount findings this scan reports that the previous one did not
 * @param resolvedCount findings the previous scan reported that this one does not
 * @param unverifiedCount findings whose movement cannot be established because a scan did not fully conclude the rule
 */
public record AdvisorRuleChangeDto(String ruleId, int newCount, int resolvedCount, int unverifiedCount) {}
