package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.conflictingSettings;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.driverEvidence;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.driverSchema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.poolSettings;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.urlSettings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fire / no-fire / skip matrix of DB-DRIVER-004..009, judged from configured driver evidence only: none of these
 * rules needs a connection, a batching verdict or any value read from a live connection.
 */
class DatabaseAdvisorDriverSettingsRulesTests {

    private static final String PGJDBC = "PostgreSQL JDBC Driver";
    private static final String CONNECTOR_J = "MySQL Connector/J";
    private static final String MARIADB_CONNECTOR = "MariaDB Connector/J";

    private final DatabaseAdvisorRule statementCache = new MySqlStatementCacheRule();
    private final DatabaseAdvisorRule pgStatementCache = new PostgresStatementCacheRule();
    private final DatabaseAdvisorRule autosave = new PostgresAutosaveRule();
    private final DatabaseAdvisorRule diagnostics = new DriverDiagnosticSettingsRule();
    private final DatabaseAdvisorRule socketTimeout = new DriverSocketTimeoutRule();
    private final DatabaseAdvisorRule applicationName = new PostgresApplicationNameRule();

    private static DatabaseAdvisorContext pg(DriverEvidence driver) {
        return context(List.of(driverSchema("ordersDb", Dialect.POSTGRESQL, driver)));
    }

    private static DatabaseAdvisorContext mysql(DriverEvidence driver) {
        return context(List.of(driverSchema("ordersDb", Dialect.MYSQL, driver)));
    }

    private static DriverEvidence pgjdbc(Map<String, DriverSetting> settings) {
        return driverEvidence(PGJDBC, settings);
    }

    private static DriverEvidence connectorJ(Map<String, DriverSetting> settings) {
        return driverEvidence(CONNECTOR_J, settings);
    }

    private static void assertSkipped(DatabaseAdvisorRuleResultDto result, String... reasonParts) {
        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains(reasonParts);
    }

    // --- DB-DRIVER-004 -------------------------------------------------------------------------------------------

    @Test
    void statementCacheFiresWhenTheCacheIsNotConfiguredAndSaysWhatToSet() {
        DatabaseAdvisorRuleResultDto result = statementCache.evaluate(mysql(connectorJ(Map.of())));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-DRIVER-004");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.category()).isEqualTo("JDBC driver");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0))
                .contains(
                        "ordersDb",
                        "cachePrepStmts",
                        "not set",
                        "(default `false`)",
                        "size >= 250",
                        "SQL limit >= 2048",
                        "Evidence: configured");
    }

    @Test
    void statementCacheFiresWhenTheCacheIsExplicitlyOffAndIgnoresTheSizeAndLimitWhileItIs() {
        DatabaseAdvisorRuleResultDto result = statementCache.evaluate(mysql(connectorJ(
                urlSettings("cachePrepStmts", "false", "prepStmtCacheSize", "10", "prepStmtCacheSqlLimit", "100"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations().get(0))
                .contains("`cachePrepStmts` is `false`", "JDBC URL")
                .doesNotContain("`prepStmtCacheSize` is", "`prepStmtCacheSqlLimit` is");
    }

    @Test
    void statementCacheListsBothSubConditionsInOneFindingWhenTheCacheIsOnWithTheDefaults() {
        DatabaseAdvisorRuleResultDto result =
                statementCache.evaluate(mysql(connectorJ(urlSettings("cachePrepStmts", "true"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0))
                .contains(
                        "`prepStmtCacheSqlLimit` is 256 (driver default), below 2048",
                        "`prepStmtCacheSize` is 25 (driver default), below 250")
                .doesNotContain("`cachePrepStmts` is");
    }

    @Test
    void statementCacheNamesOnlyTheSettingThatIsBelowItsThreshold() {
        DatabaseAdvisorRuleResultDto smallSize = statementCache.evaluate(mysql(connectorJ(
                urlSettings("cachePrepStmts", "true", "prepStmtCacheSqlLimit", "2048", "prepStmtCacheSize", "249"))));
        assertThat(smallSize.status()).isEqualTo("VIOLATION");
        assertThat(smallSize.sampleViolations().get(0))
                .contains("`prepStmtCacheSize` is 249 (JDBC URL), below 250")
                .doesNotContain("`prepStmtCacheSqlLimit` is");

        DatabaseAdvisorRuleResultDto smallLimit = statementCache.evaluate(mysql(connectorJ(
                poolSettings("cachePrepStmts", "true", "prepStmtCacheSqlLimit", "2047", "prepStmtCacheSize", "250"))));
        assertThat(smallLimit.status()).isEqualTo("VIOLATION");
        assertThat(smallLimit.sampleViolations().get(0))
                .contains("`prepStmtCacheSqlLimit` is 2047 (pool driver properties), below 2048")
                .doesNotContain("`prepStmtCacheSize` is");
    }

    @Test
    void statementCachePassesAtAndAboveTheThresholdsInAnyFormTheDriverReads() {
        for (Map<String, DriverSetting> settings : List.of(
                urlSettings("cachePrepStmts", "true", "prepStmtCacheSqlLimit", "2048", "prepStmtCacheSize", "250"),
                poolSettings("cachePrepStmts", "yes", "prepStmtCacheSqlLimit", "4096", "prepStmtCacheSize", "500"),
                // Connector/J parses a double and truncates it.
                poolSettings(
                        "cachePrepStmts", "TRUE", "prepStmtCacheSqlLimit", "2048.0", "prepStmtCacheSize", "2.5e2"))) {
            assertThat(statementCache.evaluate(mysql(connectorJ(settings))).status())
                    .isEqualTo("PASS");
        }
    }

    @Test
    void statementCacheIsSkippedWhenWhetherTheCacheIsOnCannotBeSettled() {
        assertSkipped(
                statementCache.evaluate(mysql(new DriverEvidence(CONNECTOR_J, "9", Map.of(), false))),
                "JDBC URL could not be read");
        assertSkipped(
                statementCache.evaluate(mysql(connectorJ(conflictingSettings("cachePrepStmts", "true")))),
                "cannot be settled");
        assertSkipped(
                statementCache.evaluate(mysql(connectorJ(urlSettings("cachePrepStmts", "maybe")))),
                "does not read as a boolean");
        assertSkipped(
                statementCache.evaluate(mysql(connectorJ(urlSettings("useConfigs", "maxPerformance")))),
                "file the advisor does not see");
        assertSkipped(
                statementCache.evaluate(mysql(
                        connectorJ(urlSettings("cachePrepStmts", "true", "propertiesTransform", "acme.Transform")))),
                "properties transform");
    }

    @Test
    void statementCacheIsSkippedNotPassedWhenTheSizeOrLimitCannotBeRead() {
        assertSkipped(
                statementCache.evaluate(mysql(connectorJ(urlSettings(
                        "cachePrepStmts", "true", "prepStmtCacheSqlLimit", "lots", "prepStmtCacheSize", "500")))),
                "does not read as an integer");
        assertSkipped(
                statementCache.evaluate(mysql(connectorJ(urlSettings(
                        "cachePrepStmts", "true", "prepStmtCacheSqlLimit", "4096", "prepStmtCacheSize", "NaN")))),
                "does not read as an integer");
    }

    @Test
    void statementCacheStillFiresOnAKnownSubConditionWhenAnotherSettingCannotBeRead() {
        DatabaseAdvisorRuleResultDto result = statementCache.evaluate(mysql(connectorJ(
                urlSettings("cachePrepStmts", "true", "prepStmtCacheSqlLimit", "lots", "prepStmtCacheSize", "10"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations().get(0)).contains("`prepStmtCacheSize` is 10");
    }

    @Test
    void statementCacheOnlyAppliesToMySqlConnectorJ() {
        assertSkipped(
                statementCache.evaluate(mysql(driverEvidence(MARIADB_CONNECTOR, Map.of()))), "MariaDB Connector/J");
        assertSkipped(
                statementCache.evaluate(mysql(driverEvidence("Acme MySQL Driver X", Map.of()))),
                "not MySQL Connector/J");
        assertSkipped(statementCache.evaluate(mysql(null)), "could not be identified");
        assertSkipped(statementCache.evaluate(pg(pgjdbc(Map.of()))), "Not applicable:");
    }

    @Test
    void statementCacheIncludesAMariaDbServerReachedThroughConnectorJ() {
        DatabaseAdvisorRuleResultDto result = statementCache.evaluate(
                context(List.of(driverSchema("ordersDb", Dialect.MARIADB, connectorJ(Map.of())))));

        assertThat(result.status()).isEqualTo("VIOLATION");
    }

    @Test
    void statementCacheFindingsAreKeyedByDatasourceAndSettingGroupNotByValue() {
        assertThat(AbstractDriverRule.settingIdentity("ordersDb", DriverSettingCatalog.CACHE_PREP_STMTS))
                .isEqualTo("v1:ds=ordersDb|setting=cachePrepStmts");
    }

    // --- DB-DRIVER-005 -------------------------------------------------------------------------------------------

    @Test
    void pgStatementCacheFiresForTheSimpleQueryMode() {
        DatabaseAdvisorRuleResultDto result =
                pgStatementCache.evaluate(pg(pgjdbc(urlSettings("preferQueryMode", "simple"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-DRIVER-005");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("ordersDb", "preferQueryMode", "`simple`", "JDBC URL", "Evidence: configured");
    }

    @Test
    void pgStatementCacheReadsTheQueryModeExactlyLikePgjdbcDoes() {
        for (String value : List.of("SIMPLE", "extended", "extendedForPrepared", "extendedCacheEverything", "")) {
            assertThat(pgStatementCache
                            .evaluate(pg(pgjdbc(urlSettings("preferQueryMode", value))))
                            .status())
                    .as(value)
                    .isEqualTo("PASS");
        }
    }

    @Test
    void pgStatementCacheFiresWhenEitherCacheSizeIsZeroOrNegative() {
        DatabaseAdvisorRuleResultDto queries =
                pgStatementCache.evaluate(pg(pgjdbc(poolSettings("preparedStatementCacheQueries", "0"))));
        assertThat(queries.status()).isEqualTo("VIOLATION");
        assertThat(queries.sampleViolations().get(0))
                .contains("preparedStatementCacheQueries", "is 0", "pool driver properties", "driver default 256");

        DatabaseAdvisorRuleResultDto mib =
                pgStatementCache.evaluate(pg(pgjdbc(urlSettings("preparedStatementCacheSizeMiB", "-1"))));
        assertThat(mib.status()).isEqualTo("VIOLATION");
        assertThat(mib.sampleViolations().get(0))
                .contains("preparedStatementCacheSizeMiB", "is -1", "driver default 5");
    }

    @Test
    void pgStatementCacheReportsEachDisablingSettingAsItsOwnFinding() {
        DatabaseAdvisorRuleResultDto result = pgStatementCache.evaluate(pg(pgjdbc(urlSettings(
                "preferQueryMode",
                "simple",
                "preparedStatementCacheQueries",
                "0",
                "preparedStatementCacheSizeMiB",
                "0"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(3);
    }

    @Test
    void pgStatementCachePassesWithTheDefaultsOrPositiveValues() {
        assertThat(pgStatementCache.evaluate(pg(pgjdbc(Map.of()))).status()).isEqualTo("PASS");
        assertThat(pgStatementCache
                        .evaluate(pg(pgjdbc(urlSettings(
                                "preparedStatementCacheQueries", "1", "preparedStatementCacheSizeMiB", "1"))))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void pgStatementCacheIsSkippedWhenAValueCannotBeReadOrTheSourcesAreUnknown() {
        assertSkipped(
                pgStatementCache.evaluate(pg(pgjdbc(urlSettings("preparedStatementCacheQueries", "many")))),
                "does not read as an integer");
        assertSkipped(
                pgStatementCache.evaluate(pg(new DriverEvidence(PGJDBC, "42", Map.of(), false))),
                "JDBC URL could not be read");
        assertSkipped(
                pgStatementCache.evaluate(pg(pgjdbc(urlSettings("service", "prod")))), "file the advisor does not see");
        assertSkipped(
                pgStatementCache.evaluate(pg(pgjdbc(conflictingSettings("preferQueryMode", "simple")))),
                "cannot be settled");
    }

    @Test
    void pgStatementCacheOnlyAppliesToPgjdbc() {
        assertSkipped(
                pgStatementCache.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", Map.of()))),
                "not the PostgreSQL JDBC driver");
        assertSkipped(pgStatementCache.evaluate(pg(null)), "could not be identified");
        assertSkipped(pgStatementCache.evaluate(mysql(connectorJ(Map.of()))), "Not applicable:");
    }

    // --- DB-DRIVER-006 -------------------------------------------------------------------------------------------

    @Test
    void autosaveFiresForAlwaysInAnyCaseTheDriverReads() {
        for (String value : List.of("always", "ALWAYS", "Always")) {
            DatabaseAdvisorRuleResultDto result = autosave.evaluate(pg(pgjdbc(poolSettings("autosave", value))));

            assertThat(result.status()).as(value).isEqualTo("VIOLATION");
            assertThat(result.id()).isEqualTo("DB-DRIVER-006");
            assertThat(result.severity()).isEqualTo("LOW");
            assertThat(result.sampleViolations().get(0))
                    .contains("ordersDb", "`autosave` is `always`", "pool driver properties", "driver default `never`");
        }
    }

    @Test
    void autosavePassesForNeverConservativeAndWhenAbsent() {
        assertThat(autosave.evaluate(pg(pgjdbc(Map.of()))).status()).isEqualTo("PASS");
        assertThat(autosave.evaluate(pg(pgjdbc(urlSettings("autosave", "never"))))
                        .status())
                .isEqualTo("PASS");
        assertThat(autosave.evaluate(pg(pgjdbc(urlSettings("autosave", "conservative"))))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void autosaveIsSkippedForAValueThePgjdbcWouldRefuseAndForUnknownSources() {
        assertSkipped(
                autosave.evaluate(pg(pgjdbc(urlSettings("autosave", "sometimes")))),
                "does not read as one of never, conservative or always");
        assertSkipped(autosave.evaluate(pg(pgjdbc(urlSettings("autosave", "always ")))), "does not read as");
        assertSkipped(
                autosave.evaluate(pg(new DriverEvidence(PGJDBC, "42", Map.of(), true, false))),
                "pool's driver properties could not be read");
    }

    @Test
    void autosaveOnlyAppliesToPgjdbc() {
        assertSkipped(
                autosave.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", urlSettings("autosave", "always")))),
                "not the PostgreSQL JDBC driver");
        assertSkipped(autosave.evaluate(mysql(connectorJ(urlSettings("autosave", "always")))), "Not applicable:");
    }

    // --- DB-DRIVER-007 -------------------------------------------------------------------------------------------

    @Test
    void everyConnectorJDiagnosticSettingFiresWhenTrue() {
        assertThat(DriverSettingCatalog.MYSQL_DIAGNOSTIC_SETTINGS)
                .containsExactlyInAnyOrder(
                        "profileSQL",
                        "logSlowQueries",
                        "gatherPerfMetrics",
                        "useUsageAdvisor",
                        "traceProtocol",
                        "enablePacketDebug",
                        "autoGenerateTestcaseScript",
                        "explainSlowQueries");
        for (String name : DriverSettingCatalog.MYSQL_DIAGNOSTIC_SETTINGS) {
            DatabaseAdvisorRuleResultDto result = diagnostics.evaluate(mysql(connectorJ(urlSettings(name, "true"))));

            assertThat(result.status()).as(name).isEqualTo("VIOLATION");
            assertThat(result.id()).isEqualTo("DB-DRIVER-007");
            assertThat(result.severity()).isEqualTo("LOW");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations().get(0))
                    .contains("ordersDb", "`" + name + "` is `true`", "JDBC URL", "Evidence: configured");
        }
    }

    @Test
    void connectorJDiagnosticsAcceptYesAndPassWhenFalseOrAbsent() {
        assertThat(diagnostics
                        .evaluate(mysql(connectorJ(poolSettings("profileSQL", "yes"))))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(diagnostics
                        .evaluate(mysql(connectorJ(urlSettings("profileSQL", "false", "traceProtocol", "no"))))
                        .status())
                .isEqualTo("PASS");
        assertThat(diagnostics.evaluate(mysql(connectorJ(Map.of()))).status()).isEqualTo("PASS");
    }

    @Test
    void eachEnabledDiagnosticSettingIsItsOwnFinding() {
        DatabaseAdvisorRuleResultDto result = diagnostics.evaluate(mysql(connectorJ(
                urlSettings("profileSQL", "true", "explainSlowQueries", "true", "useUsageAdvisor", "false"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(2);
    }

    @Test
    void pgjdbcFiresForLogUnclosedConnectionsOnly() {
        DatabaseAdvisorRuleResultDto result =
                diagnostics.evaluate(pg(pgjdbc(urlSettings("logUnclosedConnections", "true"))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations().get(0)).contains("`logUnclosedConnections` is `true`");

        assertThat(diagnostics
                        .evaluate(pg(pgjdbc(urlSettings("logUnclosedConnections", "false"))))
                        .status())
                .isEqualTo("PASS");
        assertThat(diagnostics
                        .evaluate(pg(pgjdbc(urlSettings("profileSQL", "true", "traceProtocol", "true"))))
                        .status())
                .as("Connector/J settings mean nothing to pgjdbc")
                .isEqualTo("PASS");
        assertThat(diagnostics
                        .evaluate(mysql(connectorJ(urlSettings("logUnclosedConnections", "true"))))
                        .status())
                .as("a pgjdbc setting means nothing to Connector/J")
                .isEqualTo("PASS");
    }

    @Test
    void diagnosticsAreSkippedNotPassedWhenAnAbsentSettingCouldBeConfiguredWhereTheAdvisorCannotSee() {
        assertSkipped(
                diagnostics.evaluate(mysql(new DriverEvidence(CONNECTOR_J, "9", Map.of(), true, false))),
                "pool's driver properties could not be read");
        assertSkipped(
                diagnostics.evaluate(mysql(connectorJ(urlSettings("useConfigs", "fullDebug")))),
                "file the advisor does not see");
        assertSkipped(
                diagnostics.evaluate(pg(new DriverEvidence(PGJDBC, "42", Map.of(), false))),
                "JDBC URL could not be read");
    }

    @Test
    void aDiagnosticSettingThatIsOnIsStillReportedWhenAnotherCannotBeSettled() {
        DatabaseAdvisorRuleResultDto result = diagnostics.evaluate(mysql(connectorJ(Map.of(
                "profileSQL", new DriverSetting("true", DriverSetting.Source.URL, DriverSetting.State.KNOWN),
                "traceProtocol",
                        new DriverSetting("true", DriverSetting.Source.URL, DriverSetting.State.CONFLICTING)))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("`profileSQL`");
    }

    @Test
    void diagnosticsOnlyApplyToMySqlConnectorJAndPgjdbc() {
        assertSkipped(
                diagnostics.evaluate(mysql(driverEvidence(MARIADB_CONNECTOR, urlSettings("profileSQL", "true")))),
                "MariaDB Connector/J");
        assertSkipped(
                diagnostics.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", Map.of()))),
                "not the PostgreSQL JDBC driver");
        assertSkipped(diagnostics.evaluate(mysql(null)), "could not be identified");
        assertSkipped(
                diagnostics.evaluate(context(List.of(
                        driverSchema("ordersDb", Dialect.GENERIC, driverEvidence("H2 JDBC Driver", Map.of()))))),
                "Not applicable:");
    }

    // --- DB-DRIVER-008 -------------------------------------------------------------------------------------------

    @Test
    void pgjdbcSocketTimeoutFiresWhenAbsentOrZeroAndSaysSeconds() {
        DatabaseAdvisorRuleResultDto absent = socketTimeout.evaluate(pg(pgjdbc(Map.of())));
        assertThat(absent.status()).isEqualTo("VIOLATION");
        assertThat(absent.id()).isEqualTo("DB-DRIVER-008");
        assertThat(absent.severity()).isEqualTo("LOW");
        assertThat(absent.sampleViolations().get(0))
                .contains(
                        "ordersDb",
                        "`socketTimeout` is not configured in the JDBC URL or pool properties",
                        "0 means no timeout; unit: seconds",
                        "Evidence: configured");

        DatabaseAdvisorRuleResultDto zero = socketTimeout.evaluate(pg(pgjdbc(urlSettings("socketTimeout", "0"))));
        assertThat(zero.status()).isEqualTo("VIOLATION");
        assertThat(zero.sampleViolations().get(0)).contains("is 0 in the JDBC URL", "unit: seconds");
    }

    @Test
    void connectorJSocketTimeoutFiresWhenAbsentOrZeroAndSaysMilliseconds() {
        DatabaseAdvisorRuleResultDto absent = socketTimeout.evaluate(mysql(connectorJ(Map.of())));
        assertThat(absent.status()).isEqualTo("VIOLATION");
        assertThat(absent.sampleViolations().get(0)).contains("not configured", "unit: milliseconds");

        DatabaseAdvisorRuleResultDto zero =
                socketTimeout.evaluate(mysql(connectorJ(poolSettings("socketTimeout", "0"))));
        assertThat(zero.status()).isEqualTo("VIOLATION");
        assertThat(zero.sampleViolations().get(0)).contains("is 0 in the pool driver properties", "unit: milliseconds");
        // Connector/J truncates a fraction: less than one millisecond is still no timeout.
        assertThat(socketTimeout
                        .evaluate(mysql(connectorJ(poolSettings("socketTimeout", "0.9"))))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void anyPositiveTimeoutPassesInTheUnitOfItsDriver() {
        assertThat(socketTimeout
                        .evaluate(pg(pgjdbc(urlSettings("socketTimeout", "1"))))
                        .status())
                .as("one second")
                .isEqualTo("PASS");
        assertThat(socketTimeout
                        .evaluate(mysql(connectorJ(urlSettings("socketTimeout", "1"))))
                        .status())
                .as("one millisecond")
                .isEqualTo("PASS");
        assertThat(socketTimeout
                        .evaluate(mysql(connectorJ(poolSettings("socketTimeout", "30000"))))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void aNegativePgjdbcTimeoutIsNoTimeoutBecausePgjdbcAppliesOnlyPositiveValues() {
        assertThat(socketTimeout
                        .evaluate(pg(pgjdbc(urlSettings("socketTimeout", "-1"))))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void socketTimeoutIsSkippedForAValueTheDriverWouldRefuse() {
        assertSkipped(
                socketTimeout.evaluate(pg(pgjdbc(urlSettings("socketTimeout", "1.5")))),
                "does not read as an integer number of seconds");
        assertSkipped(
                socketTimeout.evaluate(pg(pgjdbc(urlSettings("socketTimeout", "soon")))),
                "does not read as an integer number of seconds");
        assertSkipped(
                socketTimeout.evaluate(mysql(connectorJ(urlSettings("socketTimeout", "-5")))),
                "does not read as an integer number of milliseconds");
        assertSkipped(
                socketTimeout.evaluate(mysql(connectorJ(urlSettings("socketTimeout", "NaN")))),
                "does not read as an integer number of milliseconds");
    }

    @Test
    void socketTimeoutIsSkippedNotFiredWhenAbsenceCannotBeEstablished() {
        assertSkipped(
                socketTimeout.evaluate(pg(new DriverEvidence(PGJDBC, "42", Map.of(), false))),
                "JDBC URL could not be read");
        assertSkipped(
                socketTimeout.evaluate(mysql(new DriverEvidence(CONNECTOR_J, "9", Map.of(), true, false))),
                "pool's driver properties could not be read");
        assertSkipped(
                socketTimeout.evaluate(pg(pgjdbc(urlSettings("service", "prod")))), "file the advisor does not see");
        assertSkipped(
                socketTimeout.evaluate(mysql(connectorJ(conflictingSettings("socketTimeout", "0")))),
                "cannot be settled");
    }

    @Test
    void socketTimeoutNeverTrustsAPoolValueWhileTheUrlIsUnreadableForConnectorJ() {
        assertSkipped(
                socketTimeout.evaluate(mysql(
                        new DriverEvidence(CONNECTOR_J, "9", poolSettings("socketTimeout", "30000"), false, true))),
                "JDBC URL could not be read");
    }

    @Test
    void socketTimeoutOnlyAppliesToMySqlConnectorJAndPgjdbc() {
        assertSkipped(
                socketTimeout.evaluate(mysql(driverEvidence(MARIADB_CONNECTOR, Map.of()))), "MariaDB Connector/J");
        assertSkipped(
                socketTimeout.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", Map.of()))),
                "not the PostgreSQL JDBC driver");
        assertSkipped(socketTimeout.evaluate(pg(null)), "could not be identified");
    }

    @Test
    void aMixedInventoryJudgesEachDatasourceInItsOwnUnit() {
        DatabaseAdvisorRuleResultDto result = socketTimeout.evaluate(context(List.of(
                driverSchema("pgDb", Dialect.POSTGRESQL, pgjdbc(urlSettings("socketTimeout", "30"))),
                driverSchema("mysqlDb", Dialect.MYSQL, connectorJ(urlSettings("socketTimeout", "0"))))));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("mysqlDb", "milliseconds");
    }

    @Test
    void socketTimeoutNormalisesToMillisecondsFromTheUnitOfItsDriver() {
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, "30"))
                .isEqualTo(30_000L);
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "30"))
                .isEqualTo(30L);
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, "0"))
                .isZero();
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, "-1"))
                .isNegative();
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, "2147484"))
                .isEqualTo(2_147_484_000L);
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, "1.5"))
                .isNull();
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "-1"))
                .isNull();
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "1e3"))
                .isEqualTo(1000L);
        assertThat(DriverSettingCatalog.socketTimeoutMillis(DriverSettingCatalog.Driver.PGJDBC, null))
                .isNull();
    }

    // --- DB-DRIVER-009 -------------------------------------------------------------------------------------------

    @Test
    void applicationNameFiresWhenNotConfigured() {
        DatabaseAdvisorRuleResultDto result = applicationName.evaluate(pg(pgjdbc(Map.of())));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-DRIVER-009");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.sampleViolations().get(0))
                .contains(
                        "ordersDb",
                        "`ApplicationName` is not configured in the JDBC URL or the pool's driver properties",
                        "`PostgreSQL JDBC Driver`",
                        "pg_stat_activity",
                        "Evidence: configured");
        assertThat(result.recommendation())
                .contains("spring.datasource.hikari.data-source-properties.ApplicationName")
                .doesNotContain("spring.application.name");
    }

    @Test
    void applicationNameFiresWhenBlankOrEqualToTheDriverDefault() {
        for (String blank : List.of("", " ", "   ")) {
            DatabaseAdvisorRuleResultDto result =
                    applicationName.evaluate(pg(pgjdbc(urlSettings("ApplicationName", blank))));
            assertThat(result.status()).as("blank").isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).contains("is blank", "JDBC URL");
        }

        DatabaseAdvisorRuleResultDto asDefault =
                applicationName.evaluate(pg(pgjdbc(poolSettings("ApplicationName", "PostgreSQL JDBC Driver"))));
        assertThat(asDefault.status()).isEqualTo("VIOLATION");
        assertThat(asDefault.sampleViolations().get(0))
                .contains("is set to the driver default", "pool driver properties");
    }

    @Test
    void applicationNamePassesForACustomNameAndNeverEchoesIt() {
        assertThat(applicationName
                        .evaluate(pg(pgjdbc(urlSettings("ApplicationName", "orders-service"))))
                        .status())
                .isEqualTo("PASS");
        assertThat(applicationName
                        .evaluate(pg(pgjdbc(poolSettings("ApplicationName", "postgresql jdbc driver"))))
                        .status())
                .as("the default is compared exactly, a different spelling is a name of the application's own")
                .isEqualTo("PASS");
    }

    @Test
    void applicationNameIsSkippedForAnotherPostgresqlDriverAndWhenAbsenceIsUnknown() {
        assertSkipped(
                applicationName.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", Map.of()))),
                "not the PostgreSQL JDBC driver");
        assertSkipped(applicationName.evaluate(pg(null)), "could not be identified");
        assertSkipped(
                applicationName.evaluate(pg(new DriverEvidence(PGJDBC, "42", Map.of(), true, false))),
                "pool's driver properties could not be read");
        assertSkipped(
                applicationName.evaluate(pg(pgjdbc(conflictingSettings("ApplicationName", "x")))), "cannot be settled");
        assertSkipped(applicationName.evaluate(mysql(connectorJ(Map.of()))), "Not applicable:");
    }

    @Test
    void aUrlApplicationNameIsTrustedWhateverThePoolSaysBecauseTheUrlWins() {
        assertThat(applicationName
                        .evaluate(pg(new DriverEvidence(
                                PGJDBC, "42", urlSettings("ApplicationName", "orders-service"), true, false)))
                        .status())
                .isEqualTo("PASS");
    }

    // --- catalog -------------------------------------------------------------------------------------------------

    @Test
    void noFindingIsCutByTheFindingTextLimitEvenForALongDatasourceName() {
        String longName = "inventoryAndOrdersDb12";
        DriverEvidence pgOff = pgjdbc(poolSettings(
                "preferQueryMode", "simple", "preparedStatementCacheQueries", "-100", "autosave", "ALWAYS"));
        DriverEvidence mysqlSmall = connectorJ(poolSettings(
                "cachePrepStmts",
                "true",
                "prepStmtCacheSqlLimit",
                "1024",
                "prepStmtCacheSize",
                "100",
                "autoGenerateTestcaseScript",
                "true",
                "socketTimeout",
                "0"));
        List<DatabaseAdvisorContext> contexts = List.of(
                context(List.of(driverSchema(longName, Dialect.POSTGRESQL, pgOff))),
                context(List.of(driverSchema(longName, Dialect.POSTGRESQL, pgjdbc(Map.of())))),
                context(List.of(driverSchema(
                        longName,
                        Dialect.POSTGRESQL,
                        pgjdbc(urlSettings("ApplicationName", "PostgreSQL JDBC Driver", "socketTimeout", "-1"))))),
                context(List.of(driverSchema(longName, Dialect.MYSQL, mysqlSmall))),
                context(List.of(driverSchema(longName, Dialect.MYSQL, connectorJ(Map.of())))));
        int findings = 0;
        for (DatabaseAdvisorContext context : contexts) {
            for (DatabaseAdvisorRule rule :
                    List.of(statementCache, pgStatementCache, autosave, diagnostics, socketTimeout, applicationName)) {
                DatabaseAdvisorRuleResultDto result = rule.evaluate(context);
                for (String text : result.sampleViolations()) {
                    if (result.status().equals("VIOLATION")) {
                        findings++;
                        assertThat(text)
                                .as(rule.definition().id())
                                .doesNotEndWith("...")
                                .contains(longName);
                    }
                }
            }
        }
        assertThat(findings).isGreaterThan(10);
    }

    @Test
    void everyJudgedSettingIsAllowListedUnderItsCanonicalSpelling() {
        assertThat(DriverSettingCatalog.NAMES)
                .contains(
                        "cachePrepStmts",
                        "prepStmtCacheSize",
                        "prepStmtCacheSqlLimit",
                        "preferQueryMode",
                        "preparedStatementCacheQueries",
                        "preparedStatementCacheSizeMiB",
                        "autosave",
                        "logUnclosedConnections",
                        "socketTimeout",
                        "ApplicationName")
                .containsAll(DriverSettingCatalog.MYSQL_DIAGNOSTIC_SETTINGS);
        assertThat(DriverSettingCatalog.canonical("applicationname")).isEqualTo("ApplicationName");
        assertThat(DriverSettingCatalog.allowListed(Map.of("ApplicationName", "orders", "password", "secret")))
                .containsOnlyKeys("ApplicationName");
    }

    @Test
    void integersAreReadLikeEachDriverReadsThem() {
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.PGJDBC, "256"))
                .isEqualTo(256);
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.PGJDBC, "+5"))
                .isEqualTo(5);
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.PGJDBC, "2e3"))
                .isNull();
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.PGJDBC, " 5"))
                .isNull();
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "2e3"))
                .isEqualTo(2000);
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "2048.9"))
                .isEqualTo(2048);
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "Infinity"))
                .isNull();
        assertThat(DriverSettingCatalog.asInteger(DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J, "x"))
                .isNull();
    }

    @Test
    void theRulesAreRegisteredOnceInTheDriverCategoryWithLowSeverityForTheNewOnes() {
        List<DatabaseAdvisorRule> driverRules = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .filter(rule -> rule.definition().id().startsWith("DB-DRIVER"))
                .toList();

        assertThat(driverRules)
                .extracting(rule -> rule.definition().id())
                .containsExactly(
                        "DB-DRIVER-001",
                        "DB-DRIVER-002",
                        "DB-DRIVER-003",
                        "DB-DRIVER-004",
                        "DB-DRIVER-005",
                        "DB-DRIVER-006",
                        "DB-DRIVER-007",
                        "DB-DRIVER-008",
                        "DB-DRIVER-009");
        assertThat(driverRules.stream().skip(3))
                .extracting(rule -> rule.definition().severity())
                .containsOnly("LOW");
    }

    @Test
    void thresholdsAreNamedAndEchoedInTheFindingAndTheDescription() {
        assertThat(MySqlStatementCacheRule.RECOMMENDED_PREP_STMT_CACHE_SQL_LIMIT)
                .isEqualTo(2048);
        assertThat(MySqlStatementCacheRule.RECOMMENDED_PREP_STMT_CACHE_SIZE).isEqualTo(250);
        DatabaseAdvisorRuleResultDto result = statementCache.evaluate(mysql(connectorJ(Map.of())));
        assertThat(result.description()).contains("2048", "250", "256", "25");
        assertThat(result.learnMoreUrl()).contains("HikariCP");
    }
}
