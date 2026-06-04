package io.github.jdubois.bootui.core.dto;

/**
 * Aggregated issue or planning bucket shown on the GitHub panel.
 */
public record GitHubIssueBucketDto(String label, int count, String tone) {}
