package io.github.jdubois.bootui.core.dto;

/**
 * GitHub rate-limit or product quota card.
 */
public record GitHubQuotaDto(
        String key,
        String label,
        String category,
        String scope,
        Long limit,
        Long used,
        Long remaining,
        Long resetAt,
        Integer percentUsed,
        String status,
        String unavailableReason) {}
