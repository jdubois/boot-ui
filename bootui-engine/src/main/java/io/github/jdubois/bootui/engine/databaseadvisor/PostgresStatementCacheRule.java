package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;

/**
 * pgjdbc keeps a per-connection cache of prepared statements and normally parses each statement once. It is switched
 * off by {@code preferQueryMode=simple} (no parse, no bind), by {@code preparedStatementCacheQueries=0} and by
 * {@code preparedStatementCacheSizeMiB=0}. Judged only from configured evidence, one finding per setting that turns
 * the cache off.
 */
final class PostgresStatementCacheRule extends AbstractDriverRule {

    /** pgjdbc 42.7 {@code PGProperty.PREPARED_STATEMENT_CACHE_QUERIES} default. */
    static final int DEFAULT_PREPARED_STATEMENT_CACHE_QUERIES = 256;

    /** pgjdbc 42.7 {@code PGProperty.PREPARED_STATEMENT_CACHE_SIZE_MIB} default. */
    static final int DEFAULT_PREPARED_STATEMENT_CACHE_SIZE_MIB = 5;

    /** The only value of {@code preferQueryMode} that bypasses parse and bind; pgjdbc compares it exactly. */
    private static final String SIMPLE = "simple";

    PostgresStatementCacheRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-005",
                        "PostgreSQL driver prepared-statement cache is disabled",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using the PostgreSQL JDBC driver (pgjdbc) whose per-connection"
                                + " prepared-statement cache is disabled: preferQueryMode is simple (no parse and no"
                                + " bind, text mode only), preparedStatementCacheQueries (default "
                                + DEFAULT_PREPARED_STATEMENT_CACHE_QUERIES + ") is 0 or below, or"
                                + " preparedStatementCacheSizeMiB (default " + DEFAULT_PREPARED_STATEMENT_CACHE_SIZE_MIB
                                + ") is 0 or below (the driver treats a negative value as 0). Only configured evidence"
                                + " is read: settings the driver takes from elsewhere are not visible.",
                        "Remove the setting unless it is deliberate. pgjdbc documents preferQueryMode as meant for"
                                + " debugging, and a value of 0 for either cache setting disables the cache, so each"
                                + " statement is parsed again.",
                        "https://jdbc.postgresql.org/documentation/use/"),
                "No PostgreSQL datasource using the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = PgJdbc.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        String dataSource = schema.dataSourceName();
        List<Outcome> unresolved = new ArrayList<>();
        boolean found = false;

        SettingReading mode = SettingReading.of(driver, DriverSettingCatalog.PREFER_QUERY_MODE);
        if (mode.unresolved() != null) {
            unresolved.add(mode.unresolved());
        } else if (mode.configured() != null && SIMPLE.equals(mode.configured().value())) {
            findings.add(
                    "Datasource `" + dataSource + "`: `" + DriverSettingCatalog.PREFER_QUERY_MODE + "` is `" + SIMPLE
                            + "`, configured in the " + mode.configured().label()
                            + ", so statements are sent without parse or bind and the prepared-statement cache is"
                            + " not used. Evidence: configured.",
                    settingIdentity(dataSource, DriverSettingCatalog.PREFER_QUERY_MODE));
            found = true;
        }
        found |= judgeCache(
                driver,
                dataSource,
                DriverSettingCatalog.PREPARED_STATEMENT_CACHE_QUERIES,
                DEFAULT_PREPARED_STATEMENT_CACHE_QUERIES,
                findings,
                unresolved);
        found |= judgeCache(
                driver,
                dataSource,
                DriverSettingCatalog.PREPARED_STATEMENT_CACHE_SIZE_MIB,
                DEFAULT_PREPARED_STATEMENT_CACHE_SIZE_MIB,
                findings,
                unresolved);
        return conclude(found, unresolved);
    }

    private static boolean judgeCache(
            DriverEvidence driver,
            String dataSource,
            String name,
            int driverDefault,
            DatabaseFindings findings,
            List<Outcome> unresolved) {
        IntegerSetting setting = IntegerSetting.of(driver, name);
        if (setting.unresolved() != null) {
            unresolved.add(setting.unresolved());
            return false;
        }
        int effective = setting.effective(driverDefault);
        // pgjdbc clamps a negative size with Math.max(0, ...), so it disables the cache just as 0 does.
        if (effective > 0) {
            return false;
        }
        findings.add(
                "Datasource `" + dataSource + "`: `" + name + "` is " + effective + ", configured in the "
                        + setting.configured().label() + ", so the per-connection prepared-statement cache is"
                        + " disabled (driver default " + driverDefault + "). Evidence: configured.",
                settingIdentity(dataSource, name));
        return true;
    }
}
