package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/github")
public class GitHubController {

    private final GitHubDashboardService service;

    @Autowired
    public GitHubController(BootUiProperties properties) {
        this(new GitHubDashboardService(properties));
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
