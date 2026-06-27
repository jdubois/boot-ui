package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubDashboardServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void dashboardOnlyReadsLocalRepositoryState() throws Exception {
        Path project = githubProject("https://github.com/jdubois/boot-ui.git");
        CountingGitHubClient client = new CountingGitHubClient();
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        GitHubDashboardReport report = service.dashboard();

        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.connected()).isFalse();
        assertThat(report.repository().fullName()).isEqualTo("jdubois/boot-ui");
        assertThat(client.calls).isZero();
    }

    @Test
    void refreshUsesClientAndCachesReportForSubsequentReads() throws Exception {
        Path project = githubProject("git@github.com:jdubois/boot-ui.git");
        CountingGitHubClient client = new CountingGitHubClient();
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        GitHubDashboardReport refreshed = service.refresh();
        GitHubDashboardReport cached = service.dashboard();

        assertThat(client.calls).isEqualTo(1);
        assertThat(refreshed.status()).isEqualTo("CONNECTED");
        assertThat(cached).isSameAs(refreshed);
    }

    @Test
    void refreshDoesNotCallGitHubWhenApiRefreshIsDisabled() throws Exception {
        Path project = githubProject("https://github.com/jdubois/boot-ui.git");
        CountingGitHubClient client = new CountingGitHubClient();
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(false, List.of("api.github.com")), client);

        GitHubDashboardReport report = service.refresh();

        assertThat(report.status()).isEqualTo("DISABLED");
        assertThat(client.calls).isZero();
    }

    @Test
    void reportsUnavailableOutsideGitHubRepository() {
        GitHubDashboardService service = GitHubDashboardService.using(
                tempDir, new GitHubDashboardConfig(true, List.of("api.github.com")), new CountingGitHubClient());

        GitHubDashboardReport report = service.dashboard();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("No local git repository");
    }

    private Path githubProject(String remoteUrl) throws Exception {
        Path project = tempDir.resolve("project");
        Path git = project.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("config"), """
                [remote "origin"]
                    url = %s
                [branch "main"]
                    remote = origin
                    merge = refs/heads/main
                """.formatted(remoteUrl), StandardCharsets.UTF_8);
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        return project;
    }

    private static final class CountingGitHubClient implements GitHubClient {

        private int calls;

        @Override
        public GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository) {
            calls++;
            return new GitHubDashboardReport(
                    true,
                    null,
                    true,
                    "CONNECTED",
                    "ok",
                    Instant.now().toEpochMilli(),
                    new GitHubRepositoryDto(
                            repository.owner(),
                            repository.name(),
                            repository.fullName(),
                            repository.host(),
                            repository.apiBaseUri().toString(),
                            repository.htmlUrl(),
                            "main",
                            repository.localBranch(),
                            repository.upstreamBranch(),
                            "public",
                            false,
                            false,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new GitHubCredentialDto("test", true, null, null),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new GitHubCopilotUsageDto(
                            "UNAVAILABLE",
                            null,
                            "Copilot usage report unavailable",
                            null,
                            null,
                            null,
                            "https://docs.github.com/en/rest/copilot/copilot-usage-metrics",
                            "not refreshed"),
                    List.of());
        }
    }
}
