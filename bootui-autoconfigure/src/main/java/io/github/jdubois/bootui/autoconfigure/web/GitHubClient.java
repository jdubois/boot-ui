package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;

interface GitHubClient {

    GitHubDashboardReport refresh(GitHubRepositoryDetector.Repository repository);
}
