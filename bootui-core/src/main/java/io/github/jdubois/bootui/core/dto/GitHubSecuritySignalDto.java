package io.github.jdubois.bootui.core.dto;

/**
 * Permission-aware security signal summary from GitHub.
 */
public record GitHubSecuritySignalDto(String label, String status, Integer count, String unavailableReason) {}
