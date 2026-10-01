package io.github.jdubois.bootui.engine.hibernate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Reflective optional-provider boundary shared by native adapters. It reads a fixed scalar allowlist,
 * never acquires a connection, initializes a region, creates a session or retains native objects.
 */
public final class HibernateFactorySettingsReader {
    private HibernateFactorySettingsReader() {}

    public static HibernateFactorySettings read(
            Object factory, String label, List<HibernateObservationDiagnostic> diagnostics) {
        Reader reader = new Reader(label, diagnostics);
        Object options = reader.call(factory, "getSessionFactoryOptions");
        Object statistics = reader.call(factory, "getStatistics");
        Object properties = reader.call(factory, "getProperties");
        Map<?, ?> settings = properties instanceof Map<?, ?> map ? map : Map.of();
        Object cache = reader.call(factory, "getCache");
        Object regionFactory = reader.call(cache, "getRegionFactory");
        HibernateFactorySettings.RegionFactory region = regionFactory == null
                ? HibernateFactorySettings.RegionFactory.UNKNOWN
                : isType(regionFactory, "org.hibernate.cache.internal.NoCachingRegionFactory")
                        ? HibernateFactorySettings.RegionFactory.NONE
                        : HibernateFactorySettings.RegionFactory.AVAILABLE;
        Object registry = reader.call(factory, "getServiceRegistry");
        Object provider = reader.service(registry, "org.hibernate.engine.jdbc.connections.spi.ConnectionProvider");
        HibernateFactorySettings.ConnectionProvider connectionProvider = provider == null
                ? HibernateFactorySettings.ConnectionProvider.UNKNOWN
                : isType(provider, "org.hibernate.engine.jdbc.connections.internal.DriverManagerConnectionProviderImpl")
                        ? HibernateFactorySettings.ConnectionProvider.BUILT_IN
                        : isType(
                                                provider,
                                                "org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl")
                                        || isType(
                                                provider,
                                                "io.quarkus.hibernate.orm.runtime.customized.QuarkusConnectionProvider")
                                ? HibernateFactorySettings.ConnectionProvider.MANAGED
                                : HibernateFactorySettings.ConnectionProvider.OTHER;
        Object jdbcServices = reader.call(factory, "getJdbcServices");
        // The statement logger holds the effective show/format/slow-query values, including Hibernate defaults and
        // the legacy slow-query key, so an absent property reads as its default rather than as unknown.
        Object statementLogger = reader.call(jdbcServices, "getSqlStatementLogger");
        Object dialect = reader.call(jdbcServices, "getDialect");
        return new HibernateFactorySettings(
                reader.integer(options, "getJdbcBatchSize"),
                reader.integer(options, "getDefaultBatchFetchSize"),
                reader.bool(options, "isOrderInsertsEnabled"),
                reader.bool(options, "isOrderUpdatesEnabled"),
                reader.bool(options, "isSecondLevelCacheEnabled"),
                reader.bool(options, "isQueryCacheEnabled"),
                reader.bool(options, "isFailOnPaginationOverCollectionFetchEnabled"),
                reader.bool(statistics, "isStatisticsEnabled"),
                region,
                connectionProvider,
                schemaAction(settings),
                reader.bool(options, "isInitializeLazyStateOutsideTransactionsEnabled"),
                reader.bool(options, "inClauseParameterPaddingEnabled"),
                statementLogger == null
                        ? scalarInteger(
                                settings.containsKey("hibernate.log_slow_query")
                                        ? settings.get("hibernate.log_slow_query")
                                        : settings.get("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS"))
                        : reader.millis(statementLogger, "getLogSlowQuery"),
                statementLogger == null
                        ? scalarBoolean(settings.get("hibernate.show_sql"))
                        : reader.bool(statementLogger, "isLogToStdout"),
                statementLogger == null
                        ? scalarBoolean(settings.get("hibernate.format_sql"))
                        : reader.bool(statementLogger, "isFormat"),
                reader.bool(options, "isCommentsEnabled"),
                reader.present(options, "getJdbcTimeZone"),
                fetchSize(reader, options),
                dialectFamily(dialect, "org.hibernate.dialect.OracleDialect"),
                dialectFamily(dialect, "org.hibernate.dialect.PostgreSQLDialect"));
    }

    /** The configured JDBC fetch size, with 0 standing for an unset size that leaves the driver default in place. */
    private static Integer fetchSize(Reader reader, Object options) {
        Object value = reader.result(options, "getJdbcFetchSize");
        if (value == Reader.FAILED) return null;
        if (value == null) return 0;
        if (value instanceof Integer integer) return integer;
        reader.failed();
        return null;
    }

    /**
     * Whether the observed dialect belongs to a family. Spanner's PostgreSQL-interface dialect is not PostgreSQL, and
     * Oracle keeps its historical prefix match so versioned Oracle dialect names stay recognized.
     */
    private static Boolean dialectFamily(Object observedDialect, String familyRoot) {
        if (observedDialect != null) {
            for (Class<?> type = observedDialect.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if ("org.hibernate.dialect.SpannerPostgreSQLDialect".equals(name)) return false;
                if (familyRoot.equals(name)) return true;
                if (familyRoot.endsWith("OracleDialect") && name.startsWith("org.hibernate.dialect.Oracle"))
                    return true;
                if ("org.hibernate.dialect.Dialect".equals(name)) return false;
            }
        }
        return null;
    }

    private static HibernateFactorySettings.SchemaAction schemaAction(Map<?, ?> settings) {
        Object jpa = settings.get("jakarta.persistence.schema-generation.database.action");
        Object value = jpa != null ? jpa : settings.get("hibernate.hbm2ddl.auto");
        if (value instanceof Enum<?> action) {
            if (!"org.hibernate.tool.schema.Action"
                    .equals(action.getDeclaringClass().getName())) return HibernateFactorySettings.SchemaAction.UNKNOWN;
            return switch (action.name()) {
                case "NONE", "VALIDATE" -> HibernateFactorySettings.SchemaAction.NONE;
                case "CREATE", "CREATE_DROP" -> HibernateFactorySettings.SchemaAction.DROP_AND_CREATE;
                case "CREATE_ONLY" -> HibernateFactorySettings.SchemaAction.CREATE_ONLY;
                case "DROP" -> HibernateFactorySettings.SchemaAction.DROP;
                case "UPDATE" -> HibernateFactorySettings.SchemaAction.UPDATE;
                default -> HibernateFactorySettings.SchemaAction.UNKNOWN;
            };
        }
        return value instanceof String string
                ? jpa != null
                        ? HibernateFactorySettings.SchemaAction.jakarta(string)
                        : HibernateFactorySettings.SchemaAction.hibernate(string)
                : HibernateFactorySettings.SchemaAction.UNKNOWN;
    }

    private static boolean isType(Object value, String name) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private static Boolean scalarBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string.trim())) return true;
            if ("false".equalsIgnoreCase(string.trim())) return false;
        }
        return null;
    }

    private static Integer scalarInteger(Object value) {
        if (value instanceof Integer integer) return integer;
        if (value instanceof Long number) return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
        if (value instanceof String string) {
            try {
                return Integer.valueOf(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record Reader(String label, List<HibernateObservationDiagnostic> diagnostics) {
        static final Object FAILED = new Object();

        void failed() {
            HibernateObservationDiagnostic diagnostic = new HibernateObservationDiagnostic(
                    label, HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);
            if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
        }

        Object call(Object target, String name) {
            if (target == null) return null;
            try {
                Method method = publicMethod(target.getClass(), name);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                failed();
                return null;
            }
        }

        /** Like {@link #call}, but tells a successful {@code null} result apart from a failed or missing read. */
        Object result(Object target, String name) {
            if (target == null) {
                failed();
                return FAILED;
            }
            try {
                return publicMethod(target.getClass(), name).invoke(target);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                failed();
                return FAILED;
            }
        }

        /** Whether a nullable getter returned a value; {@code null} when the read failed. */
        Boolean present(Object target, String name) {
            Object value = result(target, name);
            return value == FAILED ? null : value != null;
        }

        Integer millis(Object target, String name) {
            Object value = call(target, name);
            if (value instanceof Number number) {
                return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number.longValue()));
            }
            failed();
            return null;
        }

        Object service(Object registry, String name) {
            if (registry == null) return null;
            try {
                Class<?> role = Class.forName(name, false, registry.getClass().getClassLoader());
                return publicMethod(registry.getClass(), "getService", Class.class)
                        .invoke(registry, role);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                failed();
                return null;
            }
        }

        Boolean bool(Object target, String name) {
            Object value = call(target, name);
            if (value instanceof Boolean bool) return bool;
            failed();
            return null;
        }

        Integer integer(Object target, String name) {
            Object value = call(target, name);
            if (value instanceof Integer integer) return integer;
            failed();
            return null;
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> contract : current.getInterfaces()) {
                if (!java.lang.reflect.Modifier.isPublic(contract.getModifiers())) continue;
                try {
                    return contract.getMethod(name, parameters);
                } catch (NoSuchMethodException ex) {
                    // Try the next public contract, not deep access to a native implementation.
                }
            }
        }
        return type.getMethod(name, parameters);
    }
}
