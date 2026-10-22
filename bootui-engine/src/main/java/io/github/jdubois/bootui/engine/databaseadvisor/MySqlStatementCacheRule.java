package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL Connector/J caches the parsing of prepared statements only when {@code cachePrepStmts} is true, and then only
 * {@code prepStmtCacheSize} statements (default 25) no longer than {@code prepStmtCacheSqlLimit} characters (default
 * 256). Judged only from configured evidence. The size and the limit have no effect while the cache is disabled, so
 * they are judged only once it is on.
 */
final class MySqlStatementCacheRule extends AbstractDriverRule {

    /**
     * The smallest {@code prepStmtCacheSqlLimit} this rule accepts. HikariCP's <em>MySQL Configuration</em> page
     * recommends 2048 and notes that the driver default of 256 "is well below the threshold of generated statement
     * lengths" with an ORM such as Hibernate.
     */
    static final int RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT = 2048;

    /**
     * The smallest {@code prepStmtCacheSize} this rule accepts: the lower end of the 250-500 that HikariCP's
     * <em>MySQL Configuration</em> page recommends, against the driver default of 25.
     */
    static final int RECOMMENDED_PREP_STMT_CACHE_SIZE = 250;

    /** Connector/J's default for {@code prepStmtCacheSqlLimit} (9.7 {@code PropertyDefinitions}). */
    static final int DEFAULT_PREP_STMT_CACHE_SQL_LIMIT = 256;

    /** Connector/J's default for {@code prepStmtCacheSize} (9.7 {@code PropertyDefinitions}). */
    static final int DEFAULT_PREP_STMT_CACHE_SIZE = 25;

    MySqlStatementCacheRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-004",
                        "MySQL Connector/J prepared-statement cache is off or small",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using MySQL Connector/J whose prepared-statement parsing cache is"
                                + " disabled (cachePrepStmts, default false, is not true) or, once enabled, is smaller"
                                + " than the HikariCP-recommended values: prepStmtCacheSqlLimit (default "
                                + DEFAULT_PREP_STMT_CACHE_SQL_LIMIT + ") below " + RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT
                                + " or prepStmtCacheSize (default " + DEFAULT_PREP_STMT_CACHE_SIZE + ") below "
                                + RECOMMENDED_PREP_STMT_CACHE_SIZE + ". The size and the limit have no effect while"
                                + " the cache is disabled. Only configured evidence is read. A MariaDB server reached"
                                + " through MySQL Connector/J is included; MariaDB Connector/J is not evaluated.",
                        "Evaluate cachePrepStmts=true, prepStmtCacheSize=" + RECOMMENDED_PREP_STMT_CACHE_SIZE
                                + " (HikariCP suggests 250-500) and prepStmtCacheSqlLimit="
                                + RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT + " in the JDBC URL or in the pool's"
                                + " data-source properties, and measure. The cache is held per connection, so a larger"
                                + " size costs memory on every pooled connection.",
                        "https://github.com/brettwooldridge/HikariCP/wiki/MySQL-Configuration"),
                "No datasource using MySQL Connector/J was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = MySqlConnectorJ.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        BooleanSetting cache = BooleanSetting.of(driver, DriverSettingCatalog.CACHE_PREP_STMTS);
        if (cache.unresolved() != null) {
            return cache.unresolved();
        }
        List<String> conditions = new ArrayList<>();
        List<Outcome> unresolved = new ArrayList<>();
        if (!cache.configuredTrue()) {
            conditions.add("`" + DriverSettingCatalog.CACHE_PREP_STMTS + "` "
                    + (cache.configured() == null
                            ? "is not set in the JDBC URL or pool properties (default `false`)"
                            : "is " + DriverRuleText.shown(cache.configured().value()) + " in the "
                                    + cache.configured().label())
                    + " (recommended: `true`, size >= "
                    + RECOMMENDED_PREP_STMT_CACHE_SIZE + ", SQL limit >= " + RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT
                    + ")");
        } else {
            judge(
                    driver,
                    DriverSettingCatalog.PREP_STMT_CACHE_SQL_LIMIT,
                    DEFAULT_PREP_STMT_CACHE_SQL_LIMIT,
                    RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT,
                    1,
                    conditions,
                    unresolved);
            judge(
                    driver,
                    DriverSettingCatalog.PREP_STMT_CACHE_SIZE,
                    DEFAULT_PREP_STMT_CACHE_SIZE,
                    RECOMMENDED_PREP_STMT_CACHE_SIZE,
                    0,
                    conditions,
                    unresolved);
        }
        if (!conditions.isEmpty()) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: Connector/J statement cache: "
                            + String.join("; ", conditions) + ". Evidence: configured.",
                    settingIdentity(schema.dataSourceName(), DriverSettingCatalog.CACHE_PREP_STMTS));
        }
        return conclude(!conditions.isEmpty(), unresolved);
    }

    private static void judge(
            DriverEvidence driver,
            String name,
            int driverDefault,
            int recommended,
            int driverMinimum,
            List<String> conditions,
            List<Outcome> unresolved) {
        IntegerSetting setting = IntegerSetting.of(driver, name, driverMinimum);
        if (setting.unresolved() != null) {
            unresolved.add(setting.unresolved());
            return;
        }
        int effective = setting.effective(driverDefault);
        if (effective < recommended) {
            conditions.add("`" + name + "` is " + effective + " ("
                    + (setting.configured() == null
                            ? "driver default"
                            : setting.configured().label()) + "), below "
                    + recommended);
        }
    }
}
