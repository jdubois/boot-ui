package io.github.jdubois.bootui.core.dto;

/**
 * A dismissed finding of one advisor and what the latest completed scan says about it.
 *
 * @param state {@code MATCHED}: the scan retained a finding with this identity; {@code ORPHANED}: the scan concluded
 *     the rule for this scope and found no such finding; {@code UNVERIFIED}: the scan cannot tell (no scan yet, the
 *     rule or scope was not concluded, or its details were truncated); {@code UNKNOWN_RULE}: the advisor has no rule
 *     with this id
 */
public record AdvisorSuppressedFindingDto(String ruleId, String identity, String state) {}
