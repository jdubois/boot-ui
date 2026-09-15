package io.github.jdubois.bootui.engine.mysql;

/** Immutable startup limits, per datasource. A full cap alone is not evidence of truncation. */
public record MySqlRowLimits(
        int maxSessions,
        int maxStatements,
        int maxIndexes,
        int maxTables,
        int maxLockWaits,
        int maxReplicationChannels,
        int maxSettings) {

    public MySqlRowLimits {
        requireValid("bootui.mysql.max-sessions", maxSessions);
        requireValid("bootui.mysql.max-statements", maxStatements);
        requireValid("bootui.mysql.max-indexes", maxIndexes);
        requireValid("bootui.mysql.max-tables", maxTables);
        requireValid("bootui.mysql.max-lock-waits", maxLockWaits);
        requireValid("bootui.mysql.max-replication-channels", maxReplicationChannels);
        requireValid("bootui.mysql.max-settings", maxSettings);
    }

    public static MySqlRowLimits defaults() {
        return new MySqlRowLimits(100, 100, 500, 200, 100, 10, 40);
    }

    public static int requireValid(String property, int value) {
        if (value <= 0 || value == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(property + " must be between 1 and 2147483646.");
        }
        return value;
    }
}
