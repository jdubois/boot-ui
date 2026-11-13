package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * What one datasource's catalog can answer, derived from its {@link Dialect} and {@link DatabaseVersion}.
 *
 * <p>Every capability gates a catalog column or view that simply does not exist on older servers, so the
 * introspector can select the right SQL up front instead of discovering the gap through a failed query —
 * and can report an honest "not supported by this server version" reason when a rule's data is missing,
 * rather than silently reporting no findings.</p>
 *
 * @param indexIncludeColumns whether {@code pg_index.indnkeyatts} is available (PostgreSQL 11+) to tell a
 *     covering index's true key columns from its {@code INCLUDE} (non-key) columns — {@code getIndexInfo()}
 *     reports both as if they were ordinary key parts
 * @param nullsNotDistinct whether {@code pg_index.indnullsnotdistinct} is available (PostgreSQL 15+) to tell
 *     a unique index that treats {@code NULL} as distinct (the default, so several {@code NULL}s are allowed)
 *     from one declared {@code NULLS NOT DISTINCT}
 * @param indexBuildProgressView whether {@code pg_stat_progress_create_index} is available (PostgreSQL 12+)
 *     to tell an index that is merely still being built {@code CONCURRENTLY} (transiently invalid) from one
 *     genuinely left behind by a failed build
 * @param oracleCatalog whether the confirmed Oracle server is new enough (19c+) for this advisor's {@code
 *     ALL_*}/{@code SYS_CONTEXT} catalog augmentation, which is written and verified against 19c+ only
 */
record DialectCapabilities(
        boolean indexVisibility,
        boolean indexExpression,
        boolean sequencesView,
        boolean declarativePartitioning,
        boolean indexIncludeColumns,
        boolean nullsNotDistinct,
        boolean indexBuildProgressView,
        boolean oracleCatalog,
        boolean checkConstraintEnforcement) {

    DialectCapabilities(
            boolean indexVisibility,
            boolean indexExpression,
            boolean sequencesView,
            boolean declarativePartitioning,
            boolean indexIncludeColumns,
            boolean nullsNotDistinct,
            boolean indexBuildProgressView,
            boolean oracleCatalog) {
        this(
                indexVisibility,
                indexExpression,
                sequencesView,
                declarativePartitioning,
                indexIncludeColumns,
                nullsNotDistinct,
                indexBuildProgressView,
                oracleCatalog,
                false);
    }

    static final DialectCapabilities NONE =
            new DialectCapabilities(false, false, false, false, false, false, false, false, false);

    static DialectCapabilities of(Dialect dialect, DatabaseVersion version) {
        return switch (dialect) {
            case POSTGRESQL ->
                new DialectCapabilities(
                        false,
                        true,
                        version.atLeast(10, 0),
                        version.atLeast(10, 0),
                        version.atLeast(11, 0),
                        version.atLeast(15, 0),
                        version.atLeast(12, 0),
                        false,
                        false);
            // MySQL 8.0 added invisible indexes (information_schema.statistics.IS_VISIBLE); 8.0.13 added
            // functional key parts and the EXPRESSION column that describes them; 8.0.16 added CHECK
            // constraint enforcement and information_schema.table_constraints.ENFORCED.
            case MYSQL ->
                new DialectCapabilities(
                        version.atLeast(8, 0),
                        version.atLeast(8, 0, 13),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        version.atLeast(8, 0, 16));
            // MariaDB 10.6 added "ignored" indexes (information_schema.statistics.IGNORED). MariaDB has no
            // EXPRESSION column in information_schema.statistics and no ENFORCED column in table_constraints.
            case MARIADB ->
                new DialectCapabilities(version.atLeast(10, 6), false, false, false, false, false, false, false, false);
            // 19c is Oracle's long-term support release and the floor this advisor is written and verified
            // against; older confirmed-Oracle servers still get the full generic JDBC ruleset, just not the
            // ALL_*/SYS_CONTEXT augmentation.
            case ORACLE ->
                new DialectCapabilities(false, false, false, false, false, false, false, version.atLeast(19, 0), false);
            case GENERIC -> NONE;
        };
    }
}
