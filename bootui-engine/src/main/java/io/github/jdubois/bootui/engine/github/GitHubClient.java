package io.github.jdubois.bootui.engine.github;

import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;

public interface GitHubClient {

    GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository);
}
