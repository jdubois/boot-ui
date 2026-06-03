package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level database connection-pool report.
 */
public record HikariPoolsReport(boolean hikariPresent, int total, List<HikariPoolDto> pools) {}
