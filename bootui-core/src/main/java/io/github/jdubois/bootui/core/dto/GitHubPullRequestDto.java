package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Bounded pull request summary for the current repository.
 */
public record GitHubPullRequestDto(
        int number,
        String title,
        String author,
        boolean draft,
        String htmlUrl,
        Long updatedAt,
        String reviewDecision,
        String checksConclusion,
        List<String> labels) {}
