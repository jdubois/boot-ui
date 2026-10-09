package io.github.jdubois.bootui.core.dto;

/**
 * Browser-safe metadata about the credential source used for GitHub calls.
 */
public record GitHubCredentialDto(String source, boolean authenticated, String login, String scopes) {}
