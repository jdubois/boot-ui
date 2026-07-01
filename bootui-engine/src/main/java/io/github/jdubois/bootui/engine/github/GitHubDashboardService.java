package io.github.jdubois.bootui.engine.github;

import io.github.jdubois.bootui.core.dto.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class GitHubDashboardService {

    private final Path workingDirectory;

    private final GitHubDashboardConfig config;

    private final GitHubClient client;

    private volatile GitHubDashboardReport lastReport;

    GitHubDashboardService(Path workingDirectory, GitHubDashboardConfig config, GitHubClient client) {
        this.workingDirectory = workingDirectory;
        this.config = config;
        this.client = client;
    }

    public static GitHubDashboardService using(
            Path workingDirectory, GitHubDashboardConfig config, GitHubClient client) {
        return new GitHubDashboardService(workingDirectory, config, client);
    }

    public GitHubDashboardReport dashboard() {
        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        workingDirectory, config.allowedApiHosts())
                .orElse(null);
        if (repository == null) {
            return unavailable(GitHubRepositoryDetector.unavailableReason(workingDirectory, config.allowedApiHosts()));
        }
        GitHubDashboardReport cached = lastReport;
        if (cached != null
                && cached.repository() != null
                && repository.fullName().equals(cached.repository().fullName())) {
            return cached;
        }
        return ready(repository, "Click Connect to load live GitHub metrics and quota state.");
    }

    public GitHubDashboardReport refresh() {
        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        workingDirectory, config.allowedApiHosts())
                .orElse(null);
        if (repository == null) {
            return unavailable(GitHubRepositoryDetector.unavailableReason(workingDirectory, config.allowedApiHosts()));
        }
        if (!config.apiEnabled()) {
            GitHubDashboardReport report = ready(
                    repository,
                    "GitHub API calls are disabled. Set bootui.github.api-enabled=true to allow the refresh action.");
            report = new GitHubDashboardReport(
                    report.available(),
                    report.unavailableReason(),
                    false,
                    "DISABLED",
                    report.message(),
                    Instant.now().toEpochMilli(),
                    report.repository(),
                    report.credential(),
                    report.metrics(),
                    report.quotas(),
                    report.pullRequests(),
                    report.workflowRuns(),
                    report.workflows(),
                    report.issueBuckets(),
                    report.issues(),
                    report.securitySignals(),
                    report.copilotUsage(),
                    report.warnings());
            lastReport = report;
            return report;
        }
        GitHubDashboardReport report = client.refresh(repository);
        if (!"ERROR".equals(report.status())) {
            lastReport = report;
        }
        return report;
    }

    private GitHubDashboardReport unavailable(String reason) {
        return new GitHubDashboardReport(
                false,
                reason,
                false,
                "UNAVAILABLE",
                reason,
                null,
                null,
                new GitHubCredentialDto("none", false, null, null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                unavailableCopilotUsage("No GitHub repository is available"),
                List.of());
    }

    private GitHubDashboardReport ready(GitHubRepositoryDetector.Repository repository, String message) {
        URI apiBase = repository.apiBaseUri();
        return new GitHubDashboardReport(
                true,
                null,
                false,
                "READY",
                message,
                null,
                new GitHubRepositoryDto(
                        repository.owner(),
                        repository.name(),
                        repository.fullName(),
                        repository.host(),
                        apiBase == null ? null : apiBase.toString(),
                        repository.htmlUrl(),
                        null,
                        repository.localBranch(),
                        repository.upstreamBranch(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                new GitHubCredentialDto("not connected", false, null, null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                unavailableCopilotUsage("Connect to GitHub to probe Copilot usage reports"),
                List.of());
    }

    private GitHubCopilotUsageDto unavailableCopilotUsage(String reason) {
        return new GitHubCopilotUsageDto(
                "UNAVAILABLE",
                null,
                "Copilot usage report unavailable",
                null,
                null,
                null,
                "https://docs.github.com/en/rest/copilot/copilot-usage-metrics",
                reason);
    }
}
