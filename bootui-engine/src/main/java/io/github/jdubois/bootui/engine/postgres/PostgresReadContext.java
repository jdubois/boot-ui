package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseVersion;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.sql.Connection;

/**
 * Everything one datasource's collectors are allowed to use: the pinned read-only connection, the server
 * version the column names are gated on, the read budget and bounds, and the live exposure policy that
 * decides how much of a normalized statement text may be shown.
 */
record PostgresReadContext(
        Connection connection,
        DatabaseVersion version,
        PostgresReadBudget budget,
        PostgresInsightLimits limits,
        ExposurePolicy exposure) {

    /** The server major version, or {@code -1} when the driver could not report it. */
    int majorVersion() {
        return version.major();
    }

    /** True when the server is at least {@code major}; {@code false} when the version is unknown. */
    boolean atLeast(int major) {
        return version.known() && version.major() >= major;
    }

    int timeoutSeconds() {
        return budget.remainingSecondsAtMost(limits.statementTimeoutSeconds());
    }
}
