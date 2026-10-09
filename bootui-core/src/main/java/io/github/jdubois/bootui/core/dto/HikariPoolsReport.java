package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level database connection-pool report.
 *
 * @param poolLibraryPresent whether a supported connection-pool library is present: HikariCP on Spring Boot, Agroal
 *     on Quarkus
 * @param total the number of pools
 * @param pools the pools, sorted by name
 */
public record HikariPoolsReport(boolean poolLibraryPresent, int total, List<HikariPoolDto> pools) {

    public HikariPoolsReport {
        pools = DtoCollections.immutableCopy(pools);
    }
}
