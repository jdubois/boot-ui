package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One page of retained, sanitized details for a finding, including dismissed findings.
 * Page totals describe retained entries; {@code violationCount} describes all counted findings.
 *
 * @param locations empty when no violation on this page has a location; otherwise aligned index-for-index with
 *     {@code violations}, with a {@code null} element for a violation that has none
 */
public record AdvisorRuleViolationsDto(
        String scanId,
        String ruleId,
        int violationCount,
        int retainedCount,
        boolean truncated,
        List<String> violations,
        PageMetadata page,
        List<AdvisorViolationLocationDto> locations) {

    public AdvisorRuleViolationsDto {
        violations = DtoCollections.immutableCopy(violations);
        locations = DtoCollections.alignedCopy(violations, locations);
    }

    public AdvisorRuleViolationsDto(
            String scanId,
            String ruleId,
            int violationCount,
            int retainedCount,
            boolean truncated,
            List<String> violations,
            PageMetadata page) {
        this(scanId, ruleId, violationCount, retainedCount, truncated, violations, page, List.of());
    }
}
