package io.github.jdubois.bootui.engine.github;

import io.github.jdubois.bootui.core.dto.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GitHubDashboardService {

    private final Path workingDirectory;

    private final GitHubDashboardConfig config;

    private final GitHubClient client;

    private volatile GitHubDashboardReport originReport;

    private volatile GitHubDashboardReport upstreamReport;

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
        return dashboard("origin");
    }

    public GitHubDashboardReport dashboard(String remote) {
        validateRemote(remote);
        GitHubRepositoryDetector.Remotes remotes =
                GitHubRepositoryDetector.detectRemotes(workingDirectory, config.allowedApiHosts());
        GitHubRepositoryContextDto context = context(remote, remotes);
        GitHubRepositoryDetector.Repository repository =
                "upstream".equals(remote) ? remotes.upstream() : remotes.origin();
        if (repository == null) {
            return unavailable(unavailableReason(remote)).withRepositoryContext(context);
        }
        GitHubDashboardReport cached = "upstream".equals(remote) ? upstreamReport : originReport;
        if (cached != null
                && cached.repository() != null
                && repository.fullName().equals(cached.repository().fullName())
                && repository.apiBaseUri().toString().equals(cached.repository().apiBaseUrl())) {
            return cached.withRepositoryContext(context);
        }
        return ready(repository, "Click Connect to load live GitHub metrics and quota state.")
                .withRepositoryContext(context);
    }

    public GitHubDashboardReport refresh() {
        return refresh("origin");
    }

    public GitHubDashboardReport refresh(String remote) {
        return refresh(remote, null, null);
    }

    public GitHubDashboardReport refresh(String remote, String expectedHost, String expectedRepository) {
        validateRemote(remote);
        validateExpectedTarget(expectedHost, expectedRepository);
        GitHubRepositoryDetector.Remotes remotes =
                GitHubRepositoryDetector.detectRemotes(workingDirectory, config.allowedApiHosts());
        GitHubRepositoryContextDto context = context(remote, remotes);
        GitHubRepositoryDetector.Repository repository =
                "upstream".equals(remote) ? remotes.upstream() : remotes.origin();
        if (repository == null) {
            return unavailable(unavailableReason(remote)).withRepositoryContext(context);
        }
        if (expectedHost != null
                && (!expectedHost.equals(repository.host()) || !expectedRepository.equals(repository.fullName()))) {
            GitHubDashboardReport report =
                    ready(repository, "The configured GitHub repository changed after page permission was granted.");
            return new GitHubDashboardReport(
                            report.available(),
                            report.unavailableReason(),
                            false,
                            "ERROR",
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
                            report.warnings())
                    .withRepositoryContext(context);
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
            report = report.withRepositoryContext(context);
            cache(remote, report);
            return report;
        }
        GitHubDashboardReport report = client.refresh(repository).withRepositoryContext(context);
        if (!"ERROR".equals(report.status())) {
            cache(remote, report);
        }
        return report;
    }

    public static void validateRemote(String remote) {
        if (!"origin".equals(remote) && !"upstream".equals(remote)) {
            throw new IllegalArgumentException("GitHub remote must be origin or upstream");
        }
    }

    public static String parseRemoteSelection(Map<String, ? extends List<String>> queryParameters) {
        for (String name : queryParameters.keySet()) {
            if ("remote".equalsIgnoreCase(name) && !"remote".equals(name)) {
                throw new IllegalArgumentException("GitHub remote must be origin or upstream");
            }
        }
        if (!queryParameters.containsKey("remote")) {
            return "origin";
        }
        List<String> values = queryParameters.get("remote");
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("GitHub remote must be origin or upstream");
        }
        String remote = values.get(0);
        validateRemote(remote);
        return remote;
    }

    public static RefreshSelection parseRefreshSelection(Map<String, ? extends List<String>> queryParameters) {
        String remote = parseRemoteSelection(queryParameters);
        for (String name : queryParameters.keySet()) {
            if (!Set.of("remote", "expectedHost", "expectedRepository").contains(name)) {
                throw new IllegalArgumentException("GitHub refresh query is invalid");
            }
        }
        String host = optionalSingle(queryParameters, "expectedHost");
        String repository = optionalSingle(queryParameters, "expectedRepository");
        validateExpectedTarget(host, repository);
        return new RefreshSelection(remote, host, repository);
    }

    private static String optionalSingle(Map<String, ? extends List<String>> queryParameters, String name) {
        if (!queryParameters.containsKey(name)) return null;
        List<String> values = queryParameters.get(name);
        if (values == null
                || values.size() != 1
                || values.get(0) == null
                || values.get(0).isBlank()) {
            throw new IllegalArgumentException("GitHub refresh target identity is invalid");
        }
        return values.get(0);
    }

    private static void validateExpectedTarget(String host, String repository) {
        if (host == null && repository == null) return;
        if (host == null
                || repository == null
                || !host.matches("[A-Za-z0-9.-]{1,253}")
                || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("GitHub refresh target identity is invalid");
        }
    }

    public record RefreshSelection(String remote, String host, String repository) {}

    private GitHubRepositoryContextDto context(String remote, GitHubRepositoryDetector.Remotes remotes) {
        return new GitHubRepositoryContextDto(
                remote,
                remoteDto("origin", remotes.origin()),
                remoteDto("upstream", remotes.upstream()),
                remotes.upstreamUnavailableReason());
    }

    private GitHubRemoteDto remoteDto(String name, GitHubRepositoryDetector.Repository repository) {
        return repository == null
                ? null
                : new GitHubRemoteDto(name, repository.fullName(), repository.host(), repository.htmlUrl());
    }

    private String unavailableReason(String remote) {
        return "upstream".equals(remote)
                ? "No valid, allowed GitHub upstream remote was detected; upstream context is unknown."
                : GitHubRepositoryDetector.unavailableReason(workingDirectory, config.allowedApiHosts());
    }

    private void cache(String remote, GitHubDashboardReport report) {
        if ("upstream".equals(remote)) {
            upstreamReport = report;
        } else {
            originReport = report;
        }
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
