package io.github.jdubois.bootui.core.dto;

/**
 * Configured GitHub Actions workflow and the latest run BootUI saw in the bounded refresh window.
 */
public record GitHubWorkflowDto(
        long id, String name, String path, String state, String htmlUrl, GitHubWorkflowRunDto latestRun) {}
