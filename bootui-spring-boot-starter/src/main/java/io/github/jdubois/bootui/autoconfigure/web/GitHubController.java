package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import io.github.jdubois.bootui.engine.support.BootUiHttpClients;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/github")
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
                        BootUiHttpClients.newBuilder()
                                .connectTimeout(properties.getGithub().getRequestTimeout())
                                .build(),
                        new ObjectMapper(),
                        DefaultGitHubTokenProvider.create())));
    }

    GitHubController(GitHubDashboardService service) {
        this.service = service;
    }

    public GitHubDashboardReport dashboard() {
        return service.dashboard();
    }

    public GitHubDashboardReport refresh() {
        return service.refresh();
    }

    @GetMapping
    public GitHubDashboardReport dashboard(@RequestParam MultiValueMap<String, String> queryParameters) {
        return service.dashboard(selectedRemote(queryParameters));
    }

    @PostMapping("/refresh")
    public GitHubDashboardReport refresh(@RequestParam MultiValueMap<String, String> queryParameters) {
        return service.refresh(selectedRemote(queryParameters));
    }

    private String selectedRemote(MultiValueMap<String, String> queryParameters) {
        try {
            return GitHubDashboardService.parseRemoteSelection(queryParameters);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
