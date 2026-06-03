package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record RepositoriesReport(boolean springDataPresent, int total, List<RepositoryDto> repositories) {}
