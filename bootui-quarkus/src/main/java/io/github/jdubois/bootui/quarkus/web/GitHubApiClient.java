package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.github.GitHubClient;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import io.github.jdubois.bootui.engine.github.GitHubTokenProvider;
import io.github.jdubois.bootui.engine.web.BoundedBodyReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Quarkus {@link GitHubClient} implementation: the Jackson 2 ({@code com.fasterxml.jackson.*}) analogue of the
 * Spring adapter's {@code GitHubApiClient}, which uses Jackson 3 ({@code tools.jackson.*}).
 *
 * <p>The shared engine is JSON-library-free on purpose (Spring Boot 4 ships Jackson 3, Quarkus ships the
 * package- and artifact-incompatible Jackson 2), so the actual GitHub REST transport and JSON parsing live in
 * each adapter. This class issues bounded, on-demand GitHub API GETs over the JDK {@link HttpClient} and shapes
 * the responses into the framework-neutral {@link GitHubDashboardReport} the engine and Vue UI consume. It is
 * driven only by the explicit refresh action ({@code POST /bootui/api/github/refresh}); it never runs on render.
 * The logic mirrors the Spring client byte-for-byte; the only differences are the Jackson import family,
 * {@link JsonNode#asText()} in place of Jackson 3's {@code asString()}, and a neutral
 * {@link QuarkusGitHubSettings} config carrier in place of {@code BootUiProperties.GitHub}.</p>
 */
public final class GitHubApiClient implements GitHubClient {

    private static final String USER_AGENT = "BootUI";

    private static final String GITHUB_API_VERSION = "2022-11-28";

    private static final String COPILOT_API_VERSION = "2026-03-10";

    private static final String COPILOT_DOCS = "https://docs.github.com/en/rest/copilot/copilot-usage-metrics";

    private final QuarkusGitHubSettings settings;

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final GitHubTokenProvider tokenProvider;

    public GitHubApiClient(
            QuarkusGitHubSettings settings,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            GitHubTokenProvider tokenProvider) {
        this.settings = settings;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository) {
        long refreshedAt = Instant.now().toEpochMilli();
        if (!apiHostAllowed(repository.apiBaseUri())) {
            return report(
                    repository,
                    false,
                    "BLOCKED",
                    "GitHub API host "
                            + repository.apiBaseUri().getHost()
                            + " is not allowed. Add it to bootui.github.allowed-api-hosts to enable refresh.",
                    refreshedAt,
                    null,
                    new GitHubCredentialDto("not connected", false, null, null),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    unavailableCopilotUsage("GitHub API host is not allowed"),
                    List.of());
        }

        GitHubTokenProvider.Token token = tokenProvider.token(settings.requestTimeout());
        RequestBudget budget = new RequestBudget(Math.max(1, settings.maxApiCalls()));
        List<String> warnings = new ArrayList<>();
        List<GitHubQuotaDto> quotas = new ArrayList<>();
        List<GitHubPullRequestDto> pullRequests = new ArrayList<>();
        List<GitHubWorkflowRunDto> workflowRuns = new ArrayList<>();
        List<GitHubWorkflowDto> workflows = new ArrayList<>();
        List<GitHubIssueBucketDto> issueBuckets = new ArrayList<>();
        List<GitHubIssueDto> issues = new ArrayList<>();
        List<GitHubSecuritySignalDto> securitySignals = new ArrayList<>();

        try {
            ApiResponse rateLimit = get(repository, "rate_limit", token, budget, "rate limits");
            if (!rateLimit.success()) {
                return report(
                        repository,
                        false,
                        "ERROR",
                        rateLimit.safeMessage(),
                        refreshedAt,
                        null,
                        credential(token, rateLimit.headers(), null),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        unavailableCopilotUsage("Rate limits could not be loaded"),
                        warnings);
            }
            quotas.addAll(rateLimitQuotas(rateLimit.json()));
            Long coreRemaining = coreRemaining(rateLimit.json());
            GitHubCopilotUsageDto copilotUsage =
                    unavailableCopilotUsage("Live repository calls were skipped to preserve remaining quota.");
            boolean quotaProtected =
                    coreRemaining != null && coreRemaining <= Math.max(0, settings.quotaSafetyThreshold());
            if (quotaProtected) {
                warnings.add(
                        "Optional GitHub calls were skipped because the core API quota is near its safety threshold.");
                return report(
                        repository,
                        true,
                        "QUOTA_PROTECTED",
                        "GitHub rate limits loaded; live repository calls were skipped to preserve remaining quota.",
                        refreshedAt,
                        null,
                        credential(token, rateLimit.headers(), null),
                        metrics(null, pullRequests, workflowRuns, issueBuckets, quotas, coreRemaining, copilotUsage),
                        quotas,
                        pullRequests,
                        workflowRuns,
                        workflows,
                        issueBuckets,
                        issues,
                        securitySignals,
                        copilotUsage,
                        warnings);
            }

            String repoPath = "repos/" + path(repository.owner()) + "/" + path(repository.name());
            ApiResponse repoResponse = get(repository, repoPath, token, budget, "repository metadata");
            JsonNode repoNode = repoResponse.success() ? repoResponse.json() : null;
            if (!repoResponse.success()) {
                warnings.add(repoResponse.safeMessage());
            }

            ApiResponse pullsResponse = get(
                    repository,
                    repoPath + "/pulls?state=open&per_page=" + positive(settings.maxPullRequests(), 10),
                    token,
                    budget,
                    "pull requests");
            if (pullsResponse.success()) {
                pullRequests.addAll(pullRequests(pullsResponse.json()));
            } else {
                warnings.add(pullsResponse.safeMessage());
            }

            ApiResponse issuesResponse = get(
                    repository,
                    repoPath + "/issues?state=open&per_page=" + positive(settings.maxIssues(), 25),
                    token,
                    budget,
                    "issues");
            if (issuesResponse.success()) {
                issueBuckets.addAll(issueBuckets(issuesResponse.json()));
                issues.addAll(issues(issuesResponse.json()));
            } else {
                warnings.add(issuesResponse.safeMessage());
            }

            ApiResponse runsResponse = get(
                    repository,
                    repoPath + "/actions/runs?per_page=" + positive(settings.maxWorkflowRuns(), 20),
                    token,
                    budget,
                    "workflow runs");
            if (runsResponse.success()) {
                workflowRuns.addAll(workflowRuns(runsResponse.json()));
            } else {
                warnings.add(runsResponse.safeMessage());
            }
            ApiResponse workflowsResponse =
                    get(repository, repoPath + "/actions/workflows?per_page=100", token, budget, "workflows");
            if (workflowsResponse.success()) {
                workflows.addAll(workflows(workflowsResponse.json(), repository, workflowRuns));
            } else {
                warnings.add(workflowsResponse.safeMessage());
            }

            addSecuritySignal(
                    securitySignals,
                    repository,
                    repoPath,
                    "Dependabot alerts",
                    "dependabot/alerts?state=open&per_page=100",
                    token,
                    budget,
                    true);
            addSecuritySignal(
                    securitySignals,
                    repository,
                    repoPath,
                    "Code scanning alerts",
                    "code-scanning/alerts?state=open&per_page=100",
                    token,
                    budget,
                    false);
            addSecuritySignal(
                    securitySignals,
                    repository,
                    repoPath,
                    "Secret scanning alerts",
                    "secret-scanning/alerts?state=open&per_page=100",
                    token,
                    budget,
                    false);
            addRepositoryQuota(
                    quotas,
                    repository,
                    repoPath,
                    "actions-cache",
                    "Actions cache",
                    "actions/cache/usage",
                    token,
                    budget);
            addRepositoryQuota(
                    quotas,
                    repository,
                    repoPath,
                    "actions-artifacts",
                    "Actions artifacts",
                    "actions/artifacts?per_page=1",
                    token,
                    budget);
            addBillingQuota(quotas, repository, repoNode, token, budget);
            copilotUsage = copilotUsage(repository, repoNode, token, budget);

            String status = warnings.isEmpty() ? "CONNECTED" : "PARTIAL";
            String message = warnings.isEmpty() ? null : "Some GitHub sections are unavailable.";
            return report(
                    repository,
                    true,
                    status,
                    message,
                    refreshedAt,
                    repoNode,
                    credential(token, rateLimit.headers(), null),
                    metrics(repoNode, pullRequests, workflowRuns, issueBuckets, quotas, coreRemaining, copilotUsage),
                    quotas,
                    pullRequests,
                    workflowRuns,
                    workflows,
                    issueBuckets,
                    issues,
                    securitySignals,
                    copilotUsage,
                    warnings);
        } catch (IOException ex) {
            return error(
                    repository,
                    refreshedAt,
                    token,
                    "Network request failed or timed out while refreshing GitHub data.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error(repository, refreshedAt, token, "GitHub refresh was interrupted before it completed.");
        }
    }

    private ApiResponse get(
            GitHubRepositoryDetector.Repository repository,
            String path,
            GitHubTokenProvider.Token token,
            RequestBudget budget,
            String label)
            throws IOException, InterruptedException {
        return get(repository, path, token, budget, label, GITHUB_API_VERSION);
    }

    private ApiResponse get(
            GitHubRepositoryDetector.Repository repository,
            String path,
            GitHubTokenProvider.Token token,
            RequestBudget budget,
            String label,
            String apiVersion)
            throws IOException, InterruptedException {
        if (!budget.tryAcquire()) {
            return ApiResponse.skipped(
                    "Skipped " + label + " because the configured GitHub API call budget was reached.");
        }
        URI uri = repository.apiBaseUri().resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", apiVersion)
                .GET();
        if (token != null && token.value() != null && !token.value().isBlank()) {
            builder.header("Authorization", "Bearer " + token.value());
        }
        HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String responseBody;
        try (InputStream stream = response.body()) {
            responseBody = BoundedBodyReader.readString(stream, BoundedBodyReader.GITHUB_MAX_BYTES);
        }
        JsonNode json = parseJson(responseBody);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new ApiResponse(response.statusCode(), json, response.headers(), null);
        }
        return new ApiResponse(
                response.statusCode(), json, response.headers(), safeMessage(label, response.statusCode()));
    }

    private JsonNode parseJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return objectMapper.readTree("{}");
        }
        return objectMapper.readTree(body);
    }

    private List<GitHubQuotaDto> rateLimitQuotas(JsonNode root) {
        JsonNode resources = root.path("resources");
        if (!resources.isObject()) {
            return List.of();
        }
        List<GitHubQuotaDto> quotas = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : resources.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            long limit = value.path("limit").asLong(-1);
            long remaining = value.path("remaining").asLong(-1);
            long used = value.path("used").asLong(limit >= 0 && remaining >= 0 ? Math.max(0, limit - remaining) : -1);
            long reset = value.path("reset").asLong(-1);
            Integer percentUsed =
                    limit > 0 && used >= 0 ? (int) Math.min(100, Math.round((used * 100.0d) / limit)) : null;
            String status = rateLimitStatus(limit, remaining);
            quotas.add(new GitHubQuotaDto(
                    key,
                    label(key),
                    "Rate limit",
                    "credential",
                    nullable(limit),
                    nullable(used),
                    nullable(remaining),
                    reset > 0 ? reset * 1000 : null,
                    percentUsed,
                    status,
                    null));
        }
        return quotas;
    }

    private String rateLimitStatus(long limit, long remaining) {
        if (remaining == 0) {
            return "EXHAUSTED";
        }
        if (limit > 0 && remaining >= 0 && ((remaining * 100.0d) / limit) <= 10.0d) {
            return "LOW";
        }
        return "OK";
    }

    private Long coreRemaining(JsonNode rateLimit) {
        long remaining =
                rateLimit.path("resources").path("core").path("remaining").asLong(-1);
        return remaining >= 0 ? remaining : null;
    }

    private List<GitHubPullRequestDto> pullRequests(JsonNode root) {
        if (!root.isArray()) {
            return List.of();
        }
        List<GitHubPullRequestDto> pullRequests = new ArrayList<>();
        for (JsonNode item : root) {
            pullRequests.add(new GitHubPullRequestDto(
                    item.path("number").asInt(),
                    text(item, "title"),
                    text(item.path("user"), "login"),
                    item.path("draft").asBoolean(false),
                    text(item, "html_url"),
                    instantMillis(text(item, "updated_at")),
                    null,
                    null,
                    labels(item.path("labels"))));
        }
        return pullRequests;
    }

    private List<GitHubWorkflowRunDto> workflowRuns(JsonNode root) {
        JsonNode runs = root.path("workflow_runs");
        if (!runs.isArray()) {
            return List.of();
        }
        List<GitHubWorkflowRunDto> workflowRuns = new ArrayList<>();
        for (JsonNode item : runs) {
            Long created = instantMillis(text(item, "created_at"));
            Long updated = instantMillis(text(item, "updated_at"));
            Long started = instantMillis(text(item, "run_started_at"));
            workflowRuns.add(new GitHubWorkflowRunDto(
                    item.path("id").asLong(),
                    nullable(item.path("workflow_id").asLong(-1)),
                    text(item, "name"),
                    text(item, "display_title"),
                    nullable(item.path("run_number").asLong(-1)),
                    text(item, "event"),
                    text(item, "status"),
                    text(item, "conclusion"),
                    text(item, "head_branch"),
                    actor(item),
                    text(item, "html_url"),
                    created,
                    updated,
                    started != null && updated != null ? Math.max(0, updated - started) : null));
        }
        return workflowRuns;
    }

    private List<GitHubWorkflowDto> workflows(
            JsonNode root, GitHubRepositoryDetector.Repository repository, List<GitHubWorkflowRunDto> workflowRuns) {
        JsonNode items = root.path("workflows");
        if (!items.isArray()) {
            return List.of();
        }
        Map<Long, GitHubWorkflowRunDto> latestRuns = latestWorkflowRuns(workflowRuns);
        List<GitHubWorkflowDto> workflows = new ArrayList<>();
        for (JsonNode item : items) {
            long id = item.path("id").asLong();
            workflows.add(new GitHubWorkflowDto(
                    id,
                    text(item, "name"),
                    text(item, "path"),
                    text(item, "state"),
                    workflowHtmlUrl(repository, item),
                    latestRuns.get(id)));
        }
        return workflows;
    }

    private Map<Long, GitHubWorkflowRunDto> latestWorkflowRuns(List<GitHubWorkflowRunDto> workflowRuns) {
        Map<Long, GitHubWorkflowRunDto> latestRuns = new HashMap<>();
        for (GitHubWorkflowRunDto run : workflowRuns) {
            if (run.workflowId() == null) {
                continue;
            }
            latestRuns.merge(
                    run.workflowId(),
                    run,
                    (current, candidate) ->
                            workflowRunTime(candidate) > workflowRunTime(current) ? candidate : current);
        }
        return latestRuns;
    }

    private long workflowRunTime(GitHubWorkflowRunDto run) {
        if (run.createdAt() != null) {
            return run.createdAt();
        }
        return run.updatedAt() == null ? 0 : run.updatedAt();
    }

    private String workflowHtmlUrl(GitHubRepositoryDetector.Repository repository, JsonNode workflow) {
        String workflowPath = text(workflow, "path");
        if (workflowPath != null && workflowPath.startsWith(".github/workflows/")) {
            String fileName = workflowPath.substring(workflowPath.lastIndexOf('/') + 1);
            return trimTrailingSlash(repository.htmlUrl()) + "/actions/workflows/" + path(fileName);
        }
        String htmlUrl = text(workflow, "html_url");
        if (htmlUrl != null) {
            return htmlUrl;
        }
        String badgeUrl = text(workflow, "badge_url");
        if (badgeUrl != null && badgeUrl.endsWith("/badge.svg")) {
            return badgeUrl.substring(0, badgeUrl.length() - "/badge.svg".length());
        }
        return trimTrailingSlash(repository.htmlUrl()) + "/actions";
    }

    private List<GitHubIssueBucketDto> issueBuckets(JsonNode root) {
        if (!root.isArray()) {
            return List.of();
        }
        int open = 0;
        int unlabeled = 0;
        int stale = 0;
        int labeled = 0;
        long staleBefore = Instant.now().minus(Duration.ofDays(30)).toEpochMilli();
        for (JsonNode item : root) {
            if (!item.path("pull_request").isMissingNode()) {
                continue;
            }
            open++;
            JsonNode labels = item.path("labels");
            if (labels.isArray() && labels.size() > 0) {
                labeled++;
            } else {
                unlabeled++;
            }
            Long updatedAt = instantMillis(text(item, "updated_at"));
            if (updatedAt != null && updatedAt < staleBefore) {
                stale++;
            }
        }
        return List.of(
                new GitHubIssueBucketDto("Open issues", open, "primary"),
                new GitHubIssueBucketDto("With labels", labeled, "info"),
                new GitHubIssueBucketDto("No label", unlabeled, "warning"),
                new GitHubIssueBucketDto("Stale 30d+", stale, stale > 0 ? "warning" : "success"));
    }

    private List<GitHubIssueDto> issues(JsonNode root) {
        if (!root.isArray()) {
            return List.of();
        }
        List<GitHubIssueDto> issues = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.path("pull_request").isMissingNode()) {
                continue;
            }
            issues.add(new GitHubIssueDto(
                    item.path("number").asInt(),
                    text(item, "title"),
                    text(item.path("user"), "login"),
                    text(item, "html_url"),
                    instantMillis(text(item, "created_at")),
                    instantMillis(text(item, "updated_at")),
                    item.path("comments").asInt(0),
                    labels(item.path("labels"))));
        }
        return issues;
    }

    private void addSecuritySignal(
            List<GitHubSecuritySignalDto> signals,
            GitHubRepositoryDetector.Repository repository,
            String repoPath,
            String label,
            String relativePath,
            GitHubTokenProvider.Token token,
            RequestBudget budget,
            boolean includeAlerts)
            throws IOException, InterruptedException {
        ApiResponse response = get(repository, repoPath + "/" + relativePath, token, budget, label);
        if (response.success()) {
            boolean array = response.json().isArray();
            signals.add(new GitHubSecuritySignalDto(
                    label,
                    "AVAILABLE",
                    array ? paginatedAlertCount(repository, relativePath, label, response, token, budget) : 0,
                    null,
                    includeAlerts && array ? dependabotAlerts(response.json()) : List.of()));
        } else {
            signals.add(new GitHubSecuritySignalDto(label, "UNAVAILABLE", null, response.safeMessage()));
        }
    }

    private List<GitHubDependabotAlertDto> dependabotAlerts(JsonNode root) {
        int max = positive(settings.maxSecurityAlerts(), 50);
        List<GitHubDependabotAlertDto> alerts = new ArrayList<>();
        for (JsonNode item : root) {
            if (alerts.size() >= max) {
                break;
            }
            JsonNode dependency = item.path("dependency");
            JsonNode pkg = dependency.path("package");
            JsonNode advisory = item.path("security_advisory");
            JsonNode vulnerability = item.path("security_vulnerability");
            String severity = textOrDefault(vulnerability, "severity", text(advisory, "severity"));
            alerts.add(new GitHubDependabotAlertDto(
                    item.path("number").asInt(),
                    text(item, "state"),
                    text(pkg, "name"),
                    text(pkg, "ecosystem"),
                    text(dependency, "manifest_path"),
                    severity,
                    text(advisory, "ghsa_id"),
                    text(advisory, "cve_id"),
                    text(advisory, "summary"),
                    text(vulnerability, "vulnerable_version_range"),
                    text(vulnerability.path("first_patched_version"), "identifier"),
                    text(item, "html_url"),
                    instantMillis(text(item, "created_at")),
                    instantMillis(text(item, "updated_at"))));
        }
        return alerts;
    }

    private int paginatedAlertCount(
            GitHubRepositoryDetector.Repository repository,
            String relativePath,
            String label,
            ApiResponse firstResponse,
            GitHubTokenProvider.Token token,
            RequestBudget budget)
            throws IOException, InterruptedException {
        int firstPageCount = firstResponse.json().size();
        Optional<Integer> lastPage = firstResponse.headers().firstValue("Link").flatMap(this::lastPage);
        if (lastPage.isEmpty() || lastPage.get() <= 1) {
            return firstPageCount;
        }
        ApiResponse lastResponse =
                get(repository, relativePath + "&page=" + lastPage.get(), token, budget, label + " final page");
        if (!lastResponse.success() || !lastResponse.json().isArray()) {
            return firstPageCount;
        }
        return ((lastPage.get() - 1) * 100) + lastResponse.json().size();
    }

    private Optional<Integer> lastPage(String linkHeader) {
        for (String link : linkHeader.split(",")) {
            if (!link.contains("rel=\"last\"")) {
                continue;
            }
            int start = link.indexOf('<');
            int end = link.indexOf('>');
            if (start < 0 || end <= start) {
                return Optional.empty();
            }
            String query = URI.create(link.substring(start + 1, end)).getQuery();
            return queryInt(query, "page");
        }
        return Optional.empty();
    }

    private Optional<Integer> queryInt(String query, String name) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            if (separator < 0 || !name.equals(parameter.substring(0, separator))) {
                continue;
            }
            try {
                return Optional.of(Integer.parseInt(parameter.substring(separator + 1)));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void addRepositoryQuota(
            List<GitHubQuotaDto> quotas,
            GitHubRepositoryDetector.Repository repository,
            String repoPath,
            String key,
            String label,
            String relativePath,
            GitHubTokenProvider.Token token,
            RequestBudget budget)
            throws IOException, InterruptedException {
        ApiResponse response = get(repository, repoPath + "/" + relativePath, token, budget, label);
        if (!response.success()) {
            quotas.add(unavailableQuota(key, label, "repository", response.safeMessage()));
            return;
        }
        if ("actions-cache".equals(key)) {
            quotas.add(new GitHubQuotaDto(
                    key,
                    label,
                    "Storage",
                    "repository",
                    null,
                    nullable(response.json().path("active_caches_size_in_bytes").asLong(-1)),
                    null,
                    null,
                    null,
                    "OK",
                    null));
        } else {
            quotas.add(new GitHubQuotaDto(
                    key,
                    label,
                    "Count",
                    "repository",
                    null,
                    nullable(response.json().path("total_count").asLong(-1)),
                    null,
                    null,
                    null,
                    "OK",
                    null));
        }
    }

    private void addBillingQuota(
            List<GitHubQuotaDto> quotas,
            GitHubRepositoryDetector.Repository repository,
            JsonNode repoNode,
            GitHubTokenProvider.Token token,
            RequestBudget budget)
            throws IOException, InterruptedException {
        String ownerType = repoNode == null ? null : text(repoNode.path("owner"), "type");
        String billingPath = "Organization".equals(ownerType)
                ? "orgs/" + path(repository.owner()) + "/settings/billing/actions"
                : "users/" + path(repository.owner()) + "/settings/billing/actions";
        ApiResponse response = get(repository, billingPath, token, budget, "Actions billing quota");
        if (!response.success()) {
            quotas.add(unavailableQuota("actions-minutes", "Actions minutes", "owner", response.safeMessage()));
            return;
        }
        long limit = response.json().path("included_minutes").asLong(-1);
        long used = response.json().path("total_minutes_used").asLong(-1);
        Long remaining = limit >= 0 && used >= 0 ? Math.max(0, limit - used) : null;
        Integer percent = limit > 0 && used >= 0 ? (int) Math.min(100, Math.round((used * 100.0d) / limit)) : null;
        quotas.add(new GitHubQuotaDto(
                "actions-minutes",
                "Actions minutes",
                "Billing",
                "owner",
                nullable(limit),
                nullable(used),
                remaining,
                null,
                percent,
                "OK",
                null));
    }

    private GitHubCopilotUsageDto copilotUsage(
            GitHubRepositoryDetector.Repository repository,
            JsonNode repoNode,
            GitHubTokenProvider.Token token,
            RequestBudget budget)
            throws IOException, InterruptedException {
        String ownerType = repoNode == null ? null : text(repoNode.path("owner"), "type");
        if (!"Organization".equals(ownerType)) {
            return unavailableCopilotUsage("Copilot organization metrics are only available for organization owners.");
        }
        ApiResponse response = get(
                repository,
                "orgs/" + path(repository.owner()) + "/copilot/metrics/reports/organization-28-day/latest",
                token,
                budget,
                "Copilot usage metrics",
                COPILOT_API_VERSION);
        if (response.status() == 204) {
            return new GitHubCopilotUsageDto(
                    "NO_DATA",
                    "organization",
                    "No Copilot usage report data was returned for this organization.",
                    null,
                    null,
                    0,
                    COPILOT_DOCS,
                    null);
        }
        if (!response.success()) {
            return unavailableCopilotUsage(response.safeMessage());
        }
        JsonNode links = response.json().path("download_links");
        int linkCount = links.isArray() ? links.size() : 0;
        return new GitHubCopilotUsageDto(
                "AVAILABLE",
                "organization",
                "Latest 28-day Copilot usage report is available.",
                text(response.json(), "report_start_day"),
                text(response.json(), "report_end_day"),
                linkCount,
                COPILOT_DOCS,
                null);
    }

    private List<GitHubMetricDto> metrics(
            JsonNode repoNode,
            List<GitHubPullRequestDto> pullRequests,
            List<GitHubWorkflowRunDto> workflowRuns,
            List<GitHubIssueBucketDto> issueBuckets,
            List<GitHubQuotaDto> quotas,
            Long coreRemaining,
            GitHubCopilotUsageDto copilotUsage) {
        long failures = currentWorkflowFailures(workflowRuns);
        String openIssues = issueBuckets.stream()
                .filter(bucket -> "Open issues".equals(bucket.label()))
                .findFirst()
                .map(bucket -> Integer.toString(bucket.count()))
                .orElse(
                        repoNode == null
                                ? "0"
                                : Long.toString(
                                        repoNode.path("open_issues_count").asLong(0)));
        return List.of(
                new GitHubMetricDto(
                        "Open pull requests", Integer.toString(pullRequests.size()), "Bounded live queue", "primary"),
                new GitHubMetricDto("Open issues", openIssues, "Issues returned by this refresh", "info"),
                new GitHubMetricDto(
                        "Workflow failures",
                        Long.toString(failures),
                        "Latest run per workflow/branch",
                        failures > 0 ? "danger" : "success"),
                new GitHubMetricDto(
                        "Core quota remaining",
                        coreRemaining == null ? "unknown" : Long.toString(coreRemaining),
                        "GitHub REST core resource",
                        coreQuotaTone(quotas)),
                copilotMetric(copilotUsage));
    }

    private String coreQuotaTone(List<GitHubQuotaDto> quotas) {
        return quotas.stream()
                .filter(quota -> "core".equals(quota.key()))
                .findFirst()
                .map(quota -> {
                    if ("EXHAUSTED".equals(quota.status())) {
                        return "danger";
                    }
                    return "LOW".equals(quota.status()) ? "warning" : "success";
                })
                .orElse("success");
    }

    private GitHubMetricDto copilotMetric(GitHubCopilotUsageDto copilotUsage) {
        if (copilotUsage != null && "AVAILABLE".equals(copilotUsage.status())) {
            return new GitHubMetricDto(
                    "Copilot usage",
                    "Available",
                    copilotUsage.reportStartDay() == null || copilotUsage.reportEndDay() == null
                            ? "Organization report links available"
                            : copilotUsage.reportStartDay() + " to " + copilotUsage.reportEndDay(),
                    "info");
        }
        if (copilotUsage != null && "NO_DATA".equals(copilotUsage.status())) {
            return new GitHubMetricDto("Copilot usage", "No data", copilotUsage.summary(), "secondary");
        }
        return new GitHubMetricDto(
                "Copilot usage",
                "Unavailable",
                copilotUsage == null ? "Copilot usage report unavailable" : copilotUsage.summary(),
                "secondary");
    }

    private boolean workflowFailure(GitHubWorkflowRunDto run) {
        if (run.conclusion() == null || run.conclusion().isBlank()) {
            return false;
        }
        return !Set.of("success", "neutral", "skipped")
                .contains(run.conclusion().toLowerCase(Locale.ROOT));
    }

    private long currentWorkflowFailures(List<GitHubWorkflowRunDto> workflowRuns) {
        Map<String, GitHubWorkflowRunDto> latestRuns = new HashMap<>();
        for (GitHubWorkflowRunDto run : workflowRuns) {
            String key = workflowRunScopeKey(run);
            latestRuns.merge(
                    key,
                    run,
                    (current, candidate) ->
                            workflowRunTime(candidate) > workflowRunTime(current) ? candidate : current);
        }
        return latestRuns.values().stream().filter(this::workflowFailure).count();
    }

    private String workflowRunScopeKey(GitHubWorkflowRunDto run) {
        if (run.workflowId() == null) {
            return "run:" + run.id();
        }
        String branch = run.branch() == null ? "" : run.branch().trim();
        return "workflow:" + run.workflowId() + ":branch:" + branch;
    }

    private GitHubDashboardReport report(
            GitHubRepositoryDetector.Repository repository,
            boolean connected,
            String status,
            String message,
            Long refreshedAt,
            JsonNode repoNode,
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
        return new GitHubDashboardReport(
                true,
                null,
                connected,
                status,
                message,
                refreshedAt,
                repository(repository, repoNode),
                credential,
                List.copyOf(metrics),
                List.copyOf(quotas),
                List.copyOf(pullRequests),
                List.copyOf(workflowRuns),
                List.copyOf(workflows),
                List.copyOf(issueBuckets),
                List.copyOf(issues),
                List.copyOf(securitySignals),
                copilotUsage,
                List.copyOf(warnings));
    }

    private GitHubDashboardReport error(
            GitHubRepositoryDetector.Repository repository,
            long refreshedAt,
            GitHubTokenProvider.Token token,
            String message) {
        return report(
                repository,
                false,
                "ERROR",
                message,
                refreshedAt,
                null,
                credential(token, null, null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                unavailableCopilotUsage(message),
                List.of(message));
    }

    private GitHubCopilotUsageDto unavailableCopilotUsage(String reason) {
        return new GitHubCopilotUsageDto(
                "UNAVAILABLE", null, "Copilot usage report unavailable", null, null, null, COPILOT_DOCS, reason);
    }

    private GitHubRepositoryDto repository(GitHubRepositoryDetector.Repository repository, JsonNode repoNode) {
        return new GitHubRepositoryDto(
                repository.owner(),
                repository.name(),
                repository.fullName(),
                repository.host(),
                repository.apiBaseUri().toString(),
                textOrDefault(repoNode, "html_url", repository.htmlUrl()),
                text(repoNode, "default_branch"),
                repository.localBranch(),
                repository.upstreamBranch(),
                text(repoNode, "visibility"),
                bool(repoNode, "private"),
                bool(repoNode, "fork"),
                bool(repoNode, "archived"),
                instantMillis(text(repoNode, "pushed_at")),
                longValue(repoNode, "stargazers_count"),
                longValue(repoNode, "forks_count"),
                longValue(repoNode, "subscribers_count"),
                longValue(repoNode, "open_issues_count"),
                null);
    }

    private GitHubCredentialDto credential(GitHubTokenProvider.Token token, HttpHeaders headers, String login) {
        if (token == null || token.value() == null || token.value().isBlank()) {
            return new GitHubCredentialDto("unauthenticated", false, null, null);
        }
        String scopes =
                headers == null ? null : headers.firstValue("X-OAuth-Scopes").orElse(null);
        return new GitHubCredentialDto(token.source(), true, login, scopes == null || scopes.isBlank() ? null : scopes);
    }

    private boolean apiHostAllowed(URI apiBaseUri) {
        String host = apiBaseUri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return settings.allowedApiHosts().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private GitHubQuotaDto unavailableQuota(String key, String label, String scope, String reason) {
        return new GitHubQuotaDto(key, label, "Quota", scope, null, null, null, null, null, "UNAVAILABLE", reason);
    }

    private String safeMessage(String label, int statusCode) {
        return "GitHub returned HTTP " + statusCode + " for " + label + ".";
    }

    private Duration timeout() {
        Duration timeout = settings.requestTimeout();
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }

    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static List<String> labels(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : node) {
            String name = text(label, "name");
            if (name != null) {
                labels.add(name);
            }
        }
        return labels;
    }

    private static String actor(JsonNode workflowRun) {
        String triggeringActor = text(workflowRun.path("triggering_actor"), "login");
        return triggeringActor == null ? text(workflowRun.path("actor"), "login") : triggeringActor;
    }

    private static String label(String key) {
        String[] parts = key.replace('_', ' ').split(" ");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
            }
        }
        return String.join(" ", words);
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String textOrDefault(JsonNode node, String fieldName, String fallback) {
        String value = text(node, fieldName);
        return value == null ? fallback : value;
    }

    private static Boolean bool(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static Long longValue(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        long result = value.asLong(-1);
        return nullable(result);
    }

    private static Long nullable(long value) {
        return value < 0 ? null : value;
    }

    private static Long instantMillis(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private record ApiResponse(int status, JsonNode json, HttpHeaders headers, String safeMessage) {

        private static ApiResponse skipped(String message) {
            return new ApiResponse(0, null, HttpHeaders.of(Map.of(), (name, value) -> true), message);
        }

        private boolean success() {
            return status >= 200 && status < 300;
        }
    }

    private static final class RequestBudget {

        private int remaining;

        private RequestBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean tryAcquire() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}
