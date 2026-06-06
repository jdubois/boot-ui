package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Bounded open issue summary for the current repository. Pull requests returned by
 * the GitHub issues endpoint are excluded.
 */
public record GitHubIssueDto(
        int number,
        String title,
        String author,
        String htmlUrl,
        Long createdAt,
        Long updatedAt,
        int comments,
        List<String> labels) {}
