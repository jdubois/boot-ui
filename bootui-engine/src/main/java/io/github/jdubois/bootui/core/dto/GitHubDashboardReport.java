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
        List<String> warnings) {

    public GitHubDashboardReport {
        metrics = DtoCollections.immutableCopy(metrics);
        quotas = DtoCollections.immutableCopy(quotas);
        pullRequests = DtoCollections.immutableCopy(pullRequests);
        workflowRuns = DtoCollections.immutableCopy(workflowRuns);
        workflows = DtoCollections.immutableCopy(workflows);
        issueBuckets = DtoCollections.immutableCopy(issueBuckets);
        issues = DtoCollections.immutableCopy(issues);
        securitySignals = DtoCollections.immutableCopy(securitySignals);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
