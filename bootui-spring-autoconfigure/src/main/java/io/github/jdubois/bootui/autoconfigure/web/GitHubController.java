package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/bootui/api/github")
public class GitHubController {

    private final GitHubDashboardService service;

    @Autowired
    public GitHubController(BootUiProperties properties) {
        this(GitHubDashboardService.using(
                Path.of(System.getProperty("user.dir", ".")),
                new GitHubDashboardConfig(
                        properties.getGithub().isApiEnabled(),
                        Arrays.asList(properties.getGithub().getAllowedApiHosts())),
                new GitHubApiClient(
                        properties.getGithub(),
                        HttpClient.newBuilder()
                                .connectTimeout(properties.getGithub().getRequestTimeout())
                                .build(),
                        new ObjectMapper(),
                        DefaultGitHubTokenProvider.create())));
    }

    GitHubController(GitHubDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public GitHubDashboardReport dashboard() {
        return service.dashboard();
    }

    @PostMapping("/refresh")
    public GitHubDashboardReport refresh() {
        return service.refresh();
    }
}
