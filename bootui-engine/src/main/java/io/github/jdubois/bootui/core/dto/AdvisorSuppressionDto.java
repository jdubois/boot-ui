package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What per-finding dismissal does to an advisor report. Dismissed findings stay in the retained details, so they can
 * be restored without a rescan; they only stop counting towards the report's totals, severity counts and score.
 *
 * @param suppressedFindings retained findings of rules that are not themselves dismissed that no longer count
 * @param incompleteRules rules with a dismissed finding whose details were truncated by the retention budget, so
 *     only the retained findings could be matched and further ones of the same identity may still count
 * @param findings the dismissed findings that belong to this advisor, with their state in the latest scan, at most
 *     the first 100
 * @param omitted how many further dismissed findings of this advisor {@code findings} leaves out
 */
public record AdvisorSuppressionDto(
        int suppressedFindings, List<String> incompleteRules, List<AdvisorSuppressedFindingDto> findings, int omitted) {

    public AdvisorSuppressionDto {
        incompleteRules = DtoCollections.immutableCopy(incompleteRules);
        findings = DtoCollections.immutableCopy(findings);
    }

    public AdvisorSuppressionDto(
            int suppressedFindings, List<String> incompleteRules, List<AdvisorSuppressedFindingDto> findings) {
        this(suppressedFindings, incompleteRules, findings, 0);
    }

    public static AdvisorSuppressionDto none() {
        return new AdvisorSuppressionDto(0, List.of(), List.of(), 0);
    }
}
