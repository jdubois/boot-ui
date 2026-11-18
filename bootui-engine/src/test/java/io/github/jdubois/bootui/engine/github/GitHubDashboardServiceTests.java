package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.core.dto.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void upstreamIsAnExplicitHostBoundSelectionWithIndependentCaches() throws Exception {
        Path project = githubProject("https://github.com/fork/service.git");
        Files.writeString(project.resolve(".git/config"), """
                [remote "upstream"]
                    url = https://ghe.example.com/team/service.git
                """, java.nio.file.StandardOpenOption.APPEND);
        CountingGitHubClient client = new CountingGitHubClient();
        client.fork = true;
        var service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("ghe.example.com")), client);

        assertThat(service.dashboard().repositoryContext().selectedRemote()).isEqualTo("origin");
        assertThat(service.dashboard("upstream").repository().host()).isEqualTo("ghe.example.com");
        assertThat(client.calls).isZero();
        var origin = service.refresh();
        assertThat(origin.repository().fork()).isTrue();
        client.fork = false;
        var upstream = service.refresh("upstream");
        assertThat(upstream.repository().fork()).isFalse();
        assertThat(upstream.repositoryContext().selectedRemote()).isEqualTo("upstream");
        assertThat(client.hosts).containsExactly("github.com", "ghe.example.com");
        assertThat(service.dashboard()).isSameAs(origin);
        assertThat(service.dashboard("upstream")).isSameAs(upstream);

        client.status = "ERROR";
        assertThat(service.refresh("upstream").status()).isEqualTo("ERROR");
        assertThat(service.dashboard("upstream")).isSameAs(upstream);
        assertThat(service.dashboard()).isSameAs(origin);
    }

    @Test
    void changingOnlyTheRemoteHostInvalidatesTheSelectedCache() throws Exception {
        Path project = githubProject("https://github.com/team/service.git");
        CountingGitHubClient client = new CountingGitHubClient();
        var service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("ghe.example.com")), client);
        service.refresh();
        Files.writeString(project.resolve(".git/config"), """
                [remote "origin"]
                    url = https://ghe.example.com/team/service.git
                """);

        var report = service.dashboard();
        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.repository().host()).isEqualTo("ghe.example.com");
        assertThat(report.repository().fork()).isNull();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void missingOrInvalidSelectionCannotFallBackToOriginOrCallTheClient() throws Exception {
        CountingGitHubClient client = new CountingGitHubClient();
        var service = GitHubDashboardService.using(
                githubProject("https://github.com/fork/service.git"),
                new GitHubDashboardConfig(true, List.of()),
                client);
        assertThat(service.refresh("upstream").status()).isEqualTo("UNAVAILABLE");
        assertThat(service.dashboard("upstream").repositoryContext().upstreamUnavailableReason())
                .contains("unknown");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.refresh("https://github.com/other/repo"))
                .withMessage("GitHub remote must be origin or upstream");
        assertThatIllegalArgumentException().isThrownBy(() -> service.dashboard("other"));
        assertThat(client.calls).isZero();
    }

    @Test
    void rawQueryDefaultsOnlyWhenAbsentAndRejectsEmptyDuplicateOrCaseAliasSelections() {
        assertThat(GitHubDashboardService.parseRemoteSelection(Map.of())).isEqualTo("origin");
        assertThat(GitHubDashboardService.parseRemoteSelection(Map.of("remote", List.of("upstream"))))
                .isEqualTo("upstream");
        for (Map<String, ? extends List<String>> query : List.of(
                Map.of("remote", List.of("")),
                Map.of("remote", List.of(" ")),
                Map.of("remote", List.of("Origin")),
                Map.of("remote", List.of("origin", "upstream")),
                Map.of("remote", List.of("upstream", "upstream")),
                Map.of("Remote", List.of("origin")),
                Map.of("remote", List.of("origin"), "REMOTE", List.of("upstream")),
                Map.of("remote", java.util.Collections.<String>singletonList(null)))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GitHubDashboardService.parseRemoteSelection(query))
                    .withMessage("GitHub remote must be origin or upstream");
        }
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

        private final List<String> hosts = new ArrayList<>();

        private Boolean fork = false;

        private String status = "CONNECTED";

        @Override
        public GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository) {
            calls++;
            hosts.add(repository.host());
            return new GitHubDashboardReport(
                    true,
                    null,
                    true,
                    status,
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
                            fork,
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
