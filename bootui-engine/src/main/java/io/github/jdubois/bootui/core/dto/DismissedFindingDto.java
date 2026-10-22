package io.github.jdubois.bootui.core.dto;

/**
 * One advisor finding the developer has dismissed, identified by its rule and canonical finding identity.
 *
 * @param note the developer's optional free-text reason, {@code null} when none was given; it is user text, served
 *     only by {@code GET /dismissed-findings} and never part of an advisor report, MCP result or CLI output
 */
public record DismissedFindingDto(String ruleId, String identity, String note) {}
