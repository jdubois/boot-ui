package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseVersion;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.sql.Connection;

/**
 * Everything one datasource's collectors are allowed to use: the pinned read-only connection, the reported
 * server version, the read budget and bounds, and the live exposure policy that decides how much of a
 * normalized statement text may be shown.
 *
 * <p>The version is reported, never relied upon to choose column names: the driver may not report it, and a
 * view or extension column can be absent on a new server or present on an old one. Collectors ask the
 * catalog instead.</p>
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

    int timeoutSeconds() {
        return budget.remainingSecondsAtMost(limits.statementTimeoutSeconds());
    }
}
