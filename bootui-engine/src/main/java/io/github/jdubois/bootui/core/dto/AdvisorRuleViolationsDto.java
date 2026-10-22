package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One page of retained, sanitized details for a finding, including dismissed findings.
 * Page totals describe retained entries; {@code violationCount} describes all counted findings.
 *
 * @param locations empty when no violation on this page has a location; otherwise aligned index-for-index with
 *     {@code violations}, with a {@code null} element for a violation that has none
 * @param identities empty when no violation on this page has a canonical identity; otherwise aligned index-for-index
 *     with {@code violations}, with a {@code null} element for an unkeyed one
 * @param changes empty when the advisor does not track changes or there is no previous scan; otherwise aligned
 *     index-for-index with {@code violations}: {@code "NEW"}, {@code "UNCHANGED"}, or {@code null} when the
 *     finding is unkeyed or its movement cannot be verified
 */
public record AdvisorRuleViolationsDto(
        String scanId,
        String ruleId,
        int violationCount,
        int retainedCount,
        boolean truncated,
        List<String> violations,
        PageMetadata page,
        List<AdvisorViolationLocationDto> locations,
        List<String> identities,
        List<String> changes) {

    public AdvisorRuleViolationsDto {
        violations = DtoCollections.immutableCopy(violations);
        locations = DtoCollections.alignedCopy(violations, locations);
        identities = DtoCollections.alignedCopy(violations, identities);
        changes = DtoCollections.alignedCopy(violations, changes);
    }

    public AdvisorRuleViolationsDto(
            String scanId,
            String ruleId,
            int violationCount,
            int retainedCount,
            boolean truncated,
            List<String> violations,
            PageMetadata page,
            List<AdvisorViolationLocationDto> locations) {
        this(scanId, ruleId, violationCount, retainedCount, truncated, violations, page, locations, List.of(), List.of());
    }

    public AdvisorRuleViolationsDto(
            String scanId,
            String ruleId,
            int violationCount,
            int retainedCount,
            boolean truncated,
            List<String> violations,
            PageMetadata page) {
        this(scanId, ruleId, violationCount, retainedCount, truncated, violations, page, List.of(), List.of(), List.of());
    }
}
