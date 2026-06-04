package io.github.jdubois.bootui.core.dto;

/**
 * Permission-aware GitHub Copilot usage report availability for the repository owner.
 */
public record GitHubCopilotUsageDto(
        String status,
        String scope,
        String summary,
        String reportStartDay,
        String reportEndDay,
        Integer downloadLinkCount,
        String documentationUrl,
        String unavailableReason) {}
