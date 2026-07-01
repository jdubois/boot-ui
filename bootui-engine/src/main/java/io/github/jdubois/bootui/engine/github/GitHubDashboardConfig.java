package io.github.jdubois.bootui.engine.github;

import java.util.List;

public record GitHubDashboardConfig(boolean apiEnabled, List<String> allowedApiHosts) {}
