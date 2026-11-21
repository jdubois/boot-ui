package io.github.jdubois.bootui.core.dto;

public record GitHubRepositoryContextDto(
        String selectedRemote, GitHubRemoteDto origin, GitHubRemoteDto upstream, String upstreamUnavailableReason) {}
