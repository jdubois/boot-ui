package io.github.jdubois.bootui.engine.hibernate;

/**
 * Allowlisted factory defaults, not a configuration dump. Null scalars and UNKNOWN classifications
 * mean unavailable, never a synthesized default. Session/query overrides are outside this observation.
 */
public record HibernateFactorySettings(
        Integer jdbcBatchSize,
        Integer defaultBatchFetchSize,
        Boolean orderInserts,
        Boolean orderUpdates,
        Boolean secondLevelCache,
        Boolean queryCache,
        Boolean paginationGuard,
        Boolean statisticsEnabled,
        RegionFactory regionFactory,
        ConnectionProvider connectionProvider,
        SchemaAction schemaAction,
        Boolean lazyLoadOutsideTransaction,
        Boolean inClausePadding,
        Integer slowQueryThreshold,
        Boolean showSql,
        Boolean formatSql,
        Boolean sqlComments,
        Boolean jdbcTimeZoneConfigured,
        Integer jdbcFetchSize,
        Boolean oracle) {

    public enum RegionFactory {
        AVAILABLE,
        NONE,
        UNKNOWN
    }

    public enum ConnectionProvider {
        BUILT_IN,
        MANAGED,
        OTHER,
        UNKNOWN
    }

    public enum SchemaAction {
        NONE,
        DROP,
        DROP_AND_CREATE,
        CREATE_ONLY,
        UPDATE,
        UNKNOWN;

        public static SchemaAction hibernate(String value) {
            if (value == null) return UNKNOWN;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "none", "validate" -> NONE;
                case "drop" -> DROP;
                case "create", "create-drop", "drop-and-create" -> DROP_AND_CREATE;
                case "create-only" -> CREATE_ONLY;
                case "update" -> UPDATE;
                default -> UNKNOWN;
            };
        }

        public static SchemaAction jakarta(String value) {
            if (value != null && "create".equalsIgnoreCase(value.trim())) return CREATE_ONLY;
            return hibernate(value);
        }
    }

    public HibernateFactorySettings {
        regionFactory = regionFactory == null ? RegionFactory.UNKNOWN : regionFactory;
        connectionProvider = connectionProvider == null ? ConnectionProvider.UNKNOWN : connectionProvider;
        schemaAction = schemaAction == null ? SchemaAction.UNKNOWN : schemaAction;
    }

    public static HibernateFactorySettings unknown() {
        return new HibernateFactorySettings(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RegionFactory.UNKNOWN,
                ConnectionProvider.UNKNOWN,
                SchemaAction.UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    String property(String key) {
        Object value =
                switch (key) {
                    case "hibernate.jdbc.batch_size" -> jdbcBatchSize;
                    case "hibernate.default_batch_fetch_size" -> defaultBatchFetchSize;
                    case "hibernate.order_inserts" -> orderInserts;
                    case "hibernate.order_updates" -> orderUpdates;
                    case "hibernate.cache.use_second_level_cache" -> secondLevelCache;
                    case "hibernate.cache.use_query_cache" -> queryCache;
                    case "hibernate.query.fail_on_pagination_over_collection_fetch" -> paginationGuard;
                    case "hibernate.generate_statistics" -> statisticsEnabled;
                    case "hibernate.enable_lazy_load_no_trans" -> lazyLoadOutsideTransaction;
                    case "hibernate.query.in_clause_parameter_padding" -> inClausePadding;
                    case "hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", "hibernate.log_slow_query" ->
                        slowQueryThreshold;
                    case "hibernate.show_sql" -> showSql;
                    case "hibernate.format_sql" -> formatSql;
                    case "hibernate.use_sql_comments" -> sqlComments;
                    case "hibernate.jdbc.time_zone" ->
                        jdbcTimeZoneConfigured == null ? null : jdbcTimeZoneConfigured ? "configured" : "";
                    case "hibernate.jdbc.fetch_size" -> jdbcFetchSize;
                    default -> null;
                };
        return value == null ? null : value.toString();
    }
}
