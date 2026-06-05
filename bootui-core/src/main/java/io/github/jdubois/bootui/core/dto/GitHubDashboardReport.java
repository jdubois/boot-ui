package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level GitHub panel payload. It contains local repository metadata and
 * bounded live GitHub metrics and quotas.
 */
public record GitHubDashboardReport(
        boolean available,
        String unavailableReason,
        boolean connected,
        String status,
        String message,
        Long refreshedAt,
        GitHubRepositoryDto repository,
        GitHubCredentialDto credential,
        List<GitHubMetricDto> metrics,
        List<GitHubQuotaDto> quotas,
        List<GitHubPullRequestDto> pullRequests,
        List<GitHubWorkflowRunDto> workflowRuns,
        List<GitHubWorkflowDto> workflows,
        List<GitHubIssueBucketDto> issueBuckets,
        List<GitHubIssueDto> issues,
        List<GitHubSecuritySignalDto> securitySignals,
        GitHubCopilotUsageDto copilotUsage,
        List<String> warnings) {}
