package io.github.jdubois.bootui.core.dto;

/**
 * Bounded workflow run summary for the current repository.
 */
public record GitHubWorkflowRunDto(
        long id,
        Long workflowId,
        String name,
        String event,
        String status,
        String conclusion,
        String branch,
        String htmlUrl,
        Long createdAt,
        Long updatedAt,
        Long durationMillis) {}
