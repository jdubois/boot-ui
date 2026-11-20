package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.GitHubCopilotUsageDto;
import io.github.jdubois.bootui.core.dto.GitHubCredentialDto;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.core.dto.GitHubRepositoryDto;
import io.github.jdubois.bootui.engine.github.GitHubClient;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link GitHubController}.
 *
 * <p>The controller is a thin pass-through over {@link GitHubDashboardService},
 * so the tests wire the real service with a controlled working directory and a
 * stub {@link GitHubClient} (no network or git binary). They assert the stable
 * {@link GitHubDashboardReport} JSON for the local-only READY dashboard, the
 * UNAVAILABLE branch outside a GitHub repository, the api-disabled refresh
 * branch, the connected refresh that delegates to the client, and an error
 * report surfaced from a failed (e.g. unauthenticated) refresh.</p>
 */
class GitHubControllerTests {

    @TempDir
    Path tempDir;

    private static MockMvc mvcFor(GitHubDashboardService service) {
        return standaloneSetup(new GitHubController(service)).build();
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

    @Test
    void dashboardReturnsLocalRepositoryState() throws Exception {
        Path project = githubProject("https://github.com/jdubois/boot-ui.git");
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        mvcFor(service)
                .perform(get("/bootui/api/github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.repository.owner").value("jdubois"))
                .andExpect(jsonPath("$.repository.name").value("boot-ui"))
                .andExpect(jsonPath("$.repository.fullName").value("jdubois/boot-ui"))
                .andExpect(jsonPath("$.credential.authenticated").value(false))
                .andExpect(jsonPath("$.copilotUsage.status").value("UNAVAILABLE"));

        assertThat(client.calls).isZero();
    }

    @Test
    void dashboardReportsUnavailableOutsideGitHubRepository() throws Exception {
        GitHubDashboardService service = GitHubDashboardService.using(
                tempDir,
                new GitHubDashboardConfig(true, List.of("api.github.com")),
                new StubGitHubClient("CONNECTED", true));

        mvcFor(service)
                .perform(get("/bootui/api/github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableReason").value(containsString("No local git repository")))
                .andExpect(jsonPath("$.copilotUsage.status").value("UNAVAILABLE"));
    }

    @Test
    void refreshReportsDisabledWhenApiRefreshIsDisabled() throws Exception {
        Path project = githubProject("https://github.com/jdubois/boot-ui.git");
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(false, List.of("api.github.com")), client);

        mvcFor(service)
                .perform(post("/bootui/api/github/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.message").value(containsString("api-enabled")))
                .andExpect(jsonPath("$.refreshedAt").isNumber());

        assertThat(client.calls).isZero();
    }

    @Test
    void refreshDelegatesToClientWhenApiEnabled() throws Exception {
        Path project = githubProject("git@github.com:jdubois/boot-ui.git");
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        mvcFor(service)
                .perform(post("/bootui/api/github/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.credential.authenticated").value(true))
                .andExpect(jsonPath("$.repository.fullName").value("jdubois/boot-ui"));

        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void optionalRemoteSelectionKeepsDefaultOriginAndRejectsArbitraryTargets() throws Exception {
        Path project = githubProject("https://origin-embedded-credential@github.com/fork/service.git");
        Files.writeString(project.resolve(".git/config"), """
                [remote "upstream"]
                    url = https://user:upstream-embedded-credential@ghe.example.com/team/service.git
                """, java.nio.file.StandardOpenOption.APPEND);
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        var service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("ghe.example.com")), client);
        MockMvc mvc = mvcFor(service);

        var response = mvc.perform(get("/bootui/api/github").param("remote", "upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository.host").value("ghe.example.com"))
                .andExpect(jsonPath("$.repositoryContext.selectedRemote").value("upstream"))
                .andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("embedded-credential", "user:");
        assertThat(client.calls).isZero();
        mvc.perform(post("/bootui/api/github/refresh").param("remote", "upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository.fullName").value("team/service"));
        mvc.perform(get("/bootui/api/github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository.fullName").value("fork/service"))
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/bootui/api/github").param("remote", "other")).andExpect(status().isBadRequest());
        mvc.perform(get("/bootui/api/github").param("remote", "")).andExpect(status().isBadRequest());
        mvc.perform(post("/bootui/api/github/refresh").param("remote", "https://github.com/other/repo"))
                .andExpect(status().isBadRequest());
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void reactiveTransportBindsExplicitRemoteWithoutCallingTheNetwork() throws Exception {
        Path project = githubProject("https://github.com/fork/service.git");
        Files.writeString(project.resolve(".git/config"), """
                [remote "upstream"]
                    url = https://ghe.example.com/team/service.git
                """, java.nio.file.StandardOpenOption.APPEND);
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        var service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("ghe.example.com")), client);
        WebTestClient web =
                WebTestClient.bindToController(new GitHubController(service)).build();

        web.get()
                .uri("/bootui/api/github?remote=upstream")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.repositoryContext.selectedRemote")
                .isEqualTo("upstream")
                .jsonPath("$.repository.host")
                .isEqualTo("ghe.example.com");
        web.get()
                .uri("/bootui/api/github")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.repositoryContext.selectedRemote")
                .isEqualTo("origin");
        web.get()
                .uri("/bootui/api/github?remote=other")
                .exchange()
                .expectStatus()
                .isBadRequest();
        web.post()
                .uri("/bootui/api/github/refresh?remote=other")
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertThat(client.calls).isZero();
    }

    @Test
    void rawQueryRejectionPrecedesIoAndCacheChangesOnBothSpringTransports() throws Exception {
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        var service = GitHubDashboardService.using(
                githubProject("https://github.com/fork/service.git"),
                new GitHubDashboardConfig(true, List.of("api.github.com")),
                client);
        var cached = service.refresh();
        MockMvc mvc = mvcFor(service);
        WebTestClient web =
                WebTestClient.bindToController(new GitHubController(service)).build();
        for (String query : new String[] {
            "?remote=",
            "?remote",
            "?remote=%20",
            "?remote=Origin",
            "?Remote=origin",
            "?remote=origin&REMOTE=upstream",
            "?remote=origin&remote=upstream",
            "?remote=upstream&remote=upstream"
        }) {
            mvc.perform(get("/bootui/api/github" + query)).andExpect(status().isBadRequest());
            mvc.perform(post("/bootui/api/github/refresh" + query)).andExpect(status().isBadRequest());
            web.get()
                    .uri("/bootui/api/github" + query)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            web.post()
                    .uri("/bootui/api/github/refresh" + query)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            assertThat(client.calls).isEqualTo(1);
            assertThat(service.dashboard()).isSameAs(cached);
        }
    }

    @Test
    void refreshReportsUnavailableOutsideGitHubRepository() throws Exception {
        StubGitHubClient client = new StubGitHubClient("CONNECTED", true);
        GitHubDashboardService service = GitHubDashboardService.using(
                tempDir, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        mvcFor(service)
                .perform(post("/bootui/api/github/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));

        assertThat(client.calls).isZero();
    }

    @Test
    void refreshSurfacesClientErrorReport() throws Exception {
        Path project = githubProject("https://github.com/jdubois/boot-ui.git");
        StubGitHubClient client = new StubGitHubClient("ERROR", false);
        GitHubDashboardService service = GitHubDashboardService.using(
                project, new GitHubDashboardConfig(true, List.of("api.github.com")), client);

        mvcFor(service)
                .perform(post("/bootui/api/github/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.credential.authenticated").value(false))
                .andExpect(jsonPath("$.warnings[0]").value(containsString("token")));

        assertThat(client.calls).isEqualTo(1);
    }

    /** Deterministic, no-network {@link GitHubClient} used to drive the refresh branches. */
    private static final class StubGitHubClient implements GitHubClient {

        private final String status;

        private final boolean connected;

        private int calls;

        private StubGitHubClient(String status, boolean connected) {
            this.status = status;
            this.connected = connected;
        }

        @Override
        public GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository) {
            calls++;
            return new GitHubDashboardReport(
                    true,
                    null,
                    connected,
                    status,
                    connected ? "ok" : "GitHub API call failed",
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
                    new GitHubCredentialDto(connected ? "test" : "none", connected, null, null),
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
                    connected ? List.of() : List.of("GitHub API call failed without a configured token"));
        }
    }
}
