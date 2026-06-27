package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
