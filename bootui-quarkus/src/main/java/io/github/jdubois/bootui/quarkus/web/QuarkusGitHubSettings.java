package io.github.jdubois.bootui.quarkus.web;

import java.time.Duration;
import java.util.List;

/**
 * Immutable tuning knobs for the Quarkus {@link GitHubApiClient}, the framework-neutral analogue of the
 * Spring adapter's {@code BootUiProperties.GitHub}.
 *
 * <p>The shared engine {@code GitHubDashboardService} is configured separately through its own
 * {@code GitHubDashboardConfig(apiEnabled, allowedApiHosts)} record; this carrier holds only the values the
 * <em>client</em> itself needs (request timeout, the per-section result caps, the quota-protection threshold,
 * the per-refresh API-call budget, and the host allow-list it enforces before issuing any request). It is
 * built once in {@code BootUiEngineProducer} from MicroProfile {@code Config}, mapping the same
 * {@code bootui.github.*} keys and defaults the Spring adapter binds.</p>
 */
public record QuarkusGitHubSettings(
        Duration requestTimeout,
        int maxPullRequests,
        int maxIssues,
        int maxWorkflowRuns,
        int quotaSafetyThreshold,
        int maxApiCalls,
        List<String> allowedApiHosts) {}
