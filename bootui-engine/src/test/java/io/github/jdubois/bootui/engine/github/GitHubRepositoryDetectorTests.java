package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitHubRepositoryDetectorTests {

    @TempDir
    Path tempDir;

    @Test
    void detectsHttpsGithubOrigin() throws Exception {
        Path project = project("https://github.com/jdubois/boot-ui.git");

        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        project, List.of("api.github.com"))
                .orElseThrow();

        assertThat(repository.fullName()).isEqualTo("jdubois/boot-ui");
        assertThat(repository.apiBaseUri().toString()).isEqualTo("https://api.github.com/");
        assertThat(repository.localBranch()).isEqualTo("main");
        assertThat(repository.upstreamBranch()).isEqualTo("main");
    }

    @Test
    void detectsScpLikeGithubOrigin() throws Exception {
        Path project = project("git@github.com:jdubois/boot-ui.git");

        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        project, List.of("api.github.com"))
                .orElseThrow();

        assertThat(repository.fullName()).isEqualTo("jdubois/boot-ui");
        assertThat(repository.htmlUrl()).isEqualTo("https://github.com/jdubois/boot-ui");
    }

    @Test
    void detectsConfiguredEnterpriseHost() throws Exception {
        Path project = project("git@ghe.example.com:team/service.git");

        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        project, List.of("ghe.example.com"))
                .orElseThrow();

        assertThat(repository.fullName()).isEqualTo("team/service");
        assertThat(repository.apiBaseUri().toString()).isEqualTo("https://ghe.example.com/api/v3/");
    }

    @ParameterizedTest
    @CsvSource({
        "https://GITHUB.COM:443/team/service.git, github.com, https://api.github.com/",
        "ssh://git@SSH.GITHUB.COM:443/team/service.git, ssh.github.com, https://api.github.com/",
        "ssh://git@GHE-ONE.EXAMPLE:2222/team/service.git, ghe-one.example, https://ghe-one.example/api/v3/",
        "https://GHE-TWO.EXAMPLE:8443/team/service.git, ghe-two.example, https://ghe-two.example/api/v3/"
    })
    void credentialLookupUsesTheDetectedHostWithoutTheGitTransportPort(String remote, String host, String apiBase)
            throws Exception {
        GitHubRepositoryDetector.Repository repository = GitHubRepositoryDetector.detect(
                        project(remote), List.of("ghe-one.example", "ghe-two.example"))
                .orElseThrow();

        assertThat(repository.host()).isEqualTo(host);
        assertThat(repository.apiBaseUri().toString()).isEqualTo(apiBase);
        GitHubTokenProvider provider = new DefaultGitHubTokenProvider(Map.of(), builder -> {
            String credentialHost = host.equals("ssh.github.com") ? "github.com" : host;
            assertThat(builder.command()).containsExactly("gh", "auth", "token", "--hostname", credentialHost);
            throw new IOException("No credentials in fixture");
        });
        assertThat(provider.token(repository.host(), Duration.ofSeconds(1))).isNull();
    }

    @Test
    void rejectsNonGithubOrigin() throws Exception {
        Path project = project("https://gitlab.com/jdubois/boot-ui.git");

        assertThat(GitHubRepositoryDetector.detect(project, List.of("api.github.com")))
                .isEmpty();
        assertThat(GitHubRepositoryDetector.unavailableReason(project, List.of("api.github.com")))
                .contains("not github.com");
    }

    private Path project(String remoteUrl) throws Exception {
        Path project = tempDir.resolve(remoteUrl.replaceAll("[^a-zA-Z0-9]", "_"));
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
}
