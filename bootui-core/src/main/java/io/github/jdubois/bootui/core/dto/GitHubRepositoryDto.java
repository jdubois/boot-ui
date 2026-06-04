package io.github.jdubois.bootui.core.dto;

/**
 * Local and remote identity for the GitHub repository backing the running app.
 */
public record GitHubRepositoryDto(
        String owner,
        String name,
        String fullName,
        String host,
        String apiBaseUrl,
        String htmlUrl,
        String defaultBranch,
        String localBranch,
        String upstreamBranch,
        String visibility,
        Boolean privateRepository,
        Boolean fork,
        Boolean archived,
        Long pushedAt,
        Long stars,
        Long forks,
        Long watchers,
        Long openIssues,
        String latestRelease) {}
