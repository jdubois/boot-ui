package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.conflictingSettings;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.driverEvidence;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.driverSchema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.poolSettings;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.urlSettings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fire / no-fire / skip matrix of DB-DRIVER-001..003, judged from configured driver evidence only. */
class DatabaseAdvisorDriverRulesTests {

    private static final String PGJDBC = "PostgreSQL JDBC Driver";
    private static final String CONNECTOR_J = "MySQL Connector/J";
    private static final String MARIADB_CONNECTOR = "MariaDB Connector/J";

    private final DatabaseAdvisorRule postgres = new PostgresRewriteBatchedInsertsRule();
    private final DatabaseAdvisorRule mysqlRewrite = new MySqlRewriteBatchedStatementsRule();
    private final DatabaseAdvisorRule affectedRows = new MySqlUseAffectedRowsRule();

    private static Map<String, JdbcBatching> batching(JdbcBatching batching) {
        return Map.of("ordersDb", batching);
    }

    private static DatabaseAdvisorContext pg(DriverEvidence driver, JdbcBatching batching) {
        return context(List.of(driverSchema("ordersDb", Dialect.POSTGRESQL, driver)), batching(batching));
    }

    private static DatabaseAdvisorContext mysql(DriverEvidence driver, JdbcBatching batching) {
        return context(List.of(driverSchema("ordersDb", Dialect.MYSQL, driver)), batching(batching));
    }

    // --- DB-DRIVER-001 -------------------------------------------------------------------------------------------

    @Test
    void postgresFiresWhenBatchingIsEnabledAndTheRewriteIsNotConfigured() {
        DatabaseAdvisorRuleResultDto result =
                postgres.evaluate(pg(driverEvidence(PGJDBC, Map.of()), JdbcBatching.enabled(30)));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-DRIVER-001");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.category()).isEqualTo("JDBC driver");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("ordersDb", "reWriteBatchedInserts", "not set", "driver default `false`", "batch size 30")
                .contains("Evidence: configured");
    }

    @Test
    void postgresFiresWhenTheRewriteIsExplicitlyFalseAndNamesWhereItWasConfigured() {
        DatabaseAdvisorRuleResultDto result = postgres.evaluate(
                pg(driverEvidence(PGJDBC, urlSettings("reWriteBatchedInserts", "false")), JdbcBatching.enabled(10)));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations().get(0)).contains("is `false`", "JDBC URL");
    }

    @Test
    void postgresTreatsAnythingButTrueAsFalseLikePgjdbcDoes() {
        assertThat(postgres.evaluate(pg(
                                driverEvidence(PGJDBC, poolSettings("reWriteBatchedInserts", "yes")),
                                JdbcBatching.enabled(10)))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void postgresPassesWhenTheRewriteIsConfiguredInTheUrlOrThePool() {
        assertThat(postgres.evaluate(pg(
                                driverEvidence(PGJDBC, urlSettings("reWriteBatchedInserts", "true")),
                                JdbcBatching.enabled(30)))
                        .status())
                .isEqualTo("PASS");
        assertThat(postgres.evaluate(pg(
                                driverEvidence(PGJDBC, poolSettings("reWriteBatchedInserts", "TRUE")),
                                JdbcBatching.enabled(30)))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void postgresIsSkippedWhenBatchingIsDisabled() {
        DatabaseAdvisorRuleResultDto result =
                postgres.evaluate(pg(driverEvidence(PGJDBC, Map.of()), JdbcBatching.disabled()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0))
                .contains("JDBC batching is not enabled for this datasource", "HIB-CONFIG-004");
    }

    @Test
    void postgresIsSkippedWhenBatchingIsUnknownOrMissing() {
        DatabaseAdvisorRuleResultDto unknown =
                postgres.evaluate(pg(driverEvidence(PGJDBC, Map.of()), JdbcBatching.unknown()));
        assertThat(unknown.status()).isEqualTo("SKIPPED");
        assertThat(unknown.sampleViolations().get(0)).contains("batching status unknown");

        DatabaseAdvisorRuleResultDto missing = postgres.evaluate(context(
                List.of(driverSchema("ordersDb", Dialect.POSTGRESQL, driverEvidence(PGJDBC, Map.of()))), Map.of()));
        assertThat(missing.status()).isEqualTo("SKIPPED");
        assertThat(missing.sampleViolations().get(0)).contains("batching status unknown");
    }

    @Test
    void postgresIsSkippedForAnotherPostgresqlDriverAndForAnUnidentifiedDriver() {
        DatabaseAdvisorRuleResultDto other =
                postgres.evaluate(pg(driverEvidence("Acme PostgreSQL Driver", Map.of()), JdbcBatching.enabled(30)));
        assertThat(other.status()).isEqualTo("SKIPPED");
        assertThat(other.sampleViolations().get(0)).contains("not the PostgreSQL JDBC driver");

        DatabaseAdvisorRuleResultDto unidentified = postgres.evaluate(pg(null, JdbcBatching.enabled(30)));
        assertThat(unidentified.status()).isEqualTo("SKIPPED");
        assertThat(unidentified.sampleViolations().get(0)).contains("could not be identified");
    }

    @Test
    void postgresIsSkippedWhenTheUrlCouldNotBeReadAndTheSettingIsAbsent() {
        DriverEvidence unreadableUrl = new DriverEvidence(PGJDBC, "42", Map.of(), false);

        DatabaseAdvisorRuleResultDto result = postgres.evaluate(pg(unreadableUrl, JdbcBatching.enabled(30)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("JDBC URL could not be read");
    }

    @Test
    void postgresCannotTrustAPoolSettingWhileTheUrlIsUnreadBecauseTheUrlWinsOverIt() {
        DriverEvidence evidence =
                new DriverEvidence(PGJDBC, "42", poolSettings("reWriteBatchedInserts", "true"), false);

        DatabaseAdvisorRuleResultDto result = postgres.evaluate(pg(evidence, JdbcBatching.enabled(30)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("JDBC URL could not be read", "may override");
    }

    @Test
    void postgresTrustsAUrlSettingWhateverThePoolSaysBecauseTheUrlWins() {
        DriverEvidence evidence =
                new DriverEvidence(PGJDBC, "42", urlSettings("reWriteBatchedInserts", "true"), true, false);

        assertThat(postgres.evaluate(pg(evidence, JdbcBatching.enabled(30))).status())
                .isEqualTo("PASS");
    }

    @Test
    void postgresIsSkippedWhenTheServiceFileCouldSupplyTheAbsentSetting() {
        DriverEvidence evidence = driverEvidence(PGJDBC, urlSettings("service", "prod"));

        DatabaseAdvisorRuleResultDto result = postgres.evaluate(pg(evidence, JdbcBatching.enabled(30)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("file the advisor does not see");
    }

    @Test
    void postgresIsNotApplicableWithoutAPostgresqlDatasource() {
        DatabaseAdvisorRuleResultDto result = postgres.evaluate(context(
                List.of(driverSchema("ordersDb", Dialect.GENERIC, driverEvidence("H2 JDBC Driver", Map.of()))),
                batching(JdbcBatching.enabled(30))));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).startsWith("Not applicable:");
    }

    // --- DB-DRIVER-002 -------------------------------------------------------------------------------------------

    @Test
    void connectorJRewriteFiresWhenBatchingIsEnabledAndTheRewriteIsNotConfigured() {
        DatabaseAdvisorRuleResultDto result =
                mysqlRewrite.evaluate(mysql(driverEvidence(CONNECTOR_J, Map.of()), JdbcBatching.enabled(25)));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-DRIVER-002");
        assertThat(result.sampleViolations().get(0))
                .contains("ordersDb", "rewriteBatchedStatements", "driver default `false`", "batch size 25");
        assertThat(result.recommendation()).contains("SQL injection", "multi-queries");
    }

    @Test
    void connectorJRewriteAcceptsYesAsTrue() {
        assertThat(mysqlRewrite
                        .evaluate(mysql(
                                driverEvidence(CONNECTOR_J, urlSettings("rewriteBatchedStatements", "yes")),
                                JdbcBatching.enabled(25)))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void connectorJRewriteIncludesAMariaDbServerReachedThroughConnectorJ() {
        DatabaseAdvisorRuleResultDto result = mysqlRewrite.evaluate(context(
                List.of(driverSchema("ordersDb", Dialect.MARIADB, driverEvidence(CONNECTOR_J, Map.of()))),
                batching(JdbcBatching.enabled(25))));

        assertThat(result.status()).isEqualTo("VIOLATION");
    }

    @Test
    void connectorJRewriteIsSkippedWithoutBatching() {
        assertThat(mysqlRewrite
                        .evaluate(mysql(driverEvidence(CONNECTOR_J, Map.of()), JdbcBatching.disabled()))
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(mysqlRewrite
                        .evaluate(mysql(driverEvidence(CONNECTOR_J, Map.of()), JdbcBatching.unknown()))
                        .sampleViolations()
                        .get(0))
                .contains("batching status unknown");
    }

    @Test
    void mariaDbConnectorIsNeverEvaluated() {
        DatabaseAdvisorContext context = context(
                List.of(driverSchema("ordersDb", Dialect.MARIADB, driverEvidence(MARIADB_CONNECTOR, Map.of()))),
                batching(JdbcBatching.enabled(25)));

        for (DatabaseAdvisorRule rule : List.of(mysqlRewrite, affectedRows)) {
            DatabaseAdvisorRuleResultDto result = rule.evaluate(context);
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(result.sampleViolations().get(0)).contains("MariaDB Connector/J", "different bulk setting");
        }
    }

    @Test
    void anUnknownDriverOnAMysqlDatasourceIsSkippedAndOnAnotherDatabaseIsNotApplicable() {
        DatabaseAdvisorRuleResultDto mysqlWithOtherDriver =
                mysqlRewrite.evaluate(mysql(driverEvidence("Acme MySQL Driver X", Map.of()), JdbcBatching.enabled(25)));
        assertThat(mysqlWithOtherDriver.status()).isEqualTo("SKIPPED");
        assertThat(mysqlWithOtherDriver.sampleViolations().get(0)).contains("not MySQL Connector/J");

        DatabaseAdvisorRuleResultDto h2 = mysqlRewrite.evaluate(context(
                List.of(driverSchema("ordersDb", Dialect.GENERIC, driverEvidence("H2 JDBC Driver", Map.of()))),
                batching(JdbcBatching.enabled(25))));
        assertThat(h2.status()).isEqualTo("SKIPPED");
        assertThat(h2.sampleViolations().get(0)).startsWith("Not applicable:");
    }

    @Test
    void connectorJRulesAreSkippedWhenTheDriverCouldNotBeIdentified() {
        assertThat(mysqlRewrite.evaluate(mysql(null, JdbcBatching.enabled(25))).status())
                .isEqualTo("SKIPPED");
        assertThat(affectedRows.evaluate(mysql(null, JdbcBatching.enabled(25))).status())
                .isEqualTo("SKIPPED");
    }

    // --- DB-DRIVER-003 -------------------------------------------------------------------------------------------

    @Test
    void useAffectedRowsFiresWhenTrueWithoutNeedingBatching() {
        for (JdbcBatching batching :
                List.of(JdbcBatching.enabled(10), JdbcBatching.disabled(), JdbcBatching.unknown())) {
            DatabaseAdvisorRuleResultDto result = affectedRows.evaluate(
                    mysql(driverEvidence(CONNECTOR_J, poolSettings("useAffectedRows", "true")), batching));

            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.id()).isEqualTo("DB-DRIVER-003");
            assertThat(result.sampleViolations().get(0))
                    .contains("ordersDb", "useAffectedRows", "pool driver properties", "Evidence: configured");
            assertThat(result.description()).contains("CLIENT_FOUND_ROWS");
        }
    }

    @Test
    void useAffectedRowsPassesWhenFalseOrAbsent() {
        assertThat(affectedRows
                        .evaluate(mysql(
                                driverEvidence(CONNECTOR_J, urlSettings("useAffectedRows", "false")),
                                JdbcBatching.unknown()))
                        .status())
                .isEqualTo("PASS");
        assertThat(affectedRows
                        .evaluate(mysql(driverEvidence(CONNECTOR_J, Map.of()), JdbcBatching.unknown()))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void useAffectedRowsIsSkippedWhenThePoolsDriverPropertiesCouldNotBeRead() {
        DriverEvidence unreadablePool = new DriverEvidence(CONNECTOR_J, "9", Map.of(), true, false);

        DatabaseAdvisorRuleResultDto result = affectedRows.evaluate(mysql(unreadablePool, JdbcBatching.unknown()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("pool's driver properties could not be read");
    }

    @Test
    void useAffectedRowsFromTheUrlIsNotTrustedWhileThePoolCouldOverrideIt() {
        DriverEvidence evidence =
                new DriverEvidence(CONNECTOR_J, "9", urlSettings("useAffectedRows", "true"), true, false);

        assertThat(affectedRows
                        .evaluate(mysql(evidence, JdbcBatching.unknown()))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void aPropertiesTransformCanReplaceAnExplicitValueSoNoVerdictIsGiven() {
        DriverEvidence evidence = driverEvidence(
                CONNECTOR_J, urlSettings("rewriteBatchedStatements", "true", "propertiesTransform", "acme.Transform"));

        DatabaseAdvisorRuleResultDto result = mysqlRewrite.evaluate(mysql(evidence, JdbcBatching.enabled(25)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("properties transform");
        assertThat(affectedRows
                        .evaluate(mysql(
                                driverEvidence(
                                        CONNECTOR_J,
                                        urlSettings(
                                                "useAffectedRows", "true", "propertiesTransform", "acme.Transform")),
                                JdbcBatching.unknown()))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void useAffectedRowsIsSkippedWhenAConfigFileCouldSupplyIt() {
        DriverEvidence evidence = driverEvidence(CONNECTOR_J, urlSettings("useConfigs", "maxPerformance"));

        assertThat(affectedRows
                        .evaluate(mysql(evidence, JdbcBatching.unknown()))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void useAffectedRowsIsSkippedWhenTheUrlCouldNotBeReadAndTheSettingIsAbsent() {
        DriverEvidence unreadableUrl = new DriverEvidence(CONNECTOR_J, "9", Map.of(), false);

        DatabaseAdvisorRuleResultDto result = affectedRows.evaluate(mysql(unreadableUrl, JdbcBatching.unknown()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("JDBC URL could not be read");
    }

    // --- conflicting evidence and mixed inventories --------------------------------------------------------------

    @Test
    void conflictingUrlAndPoolValuesSkipEveryRuleThatNeedsTheSetting() {
        DriverEvidence rewrite = driverEvidence(CONNECTOR_J, conflictingSettings("rewriteBatchedStatements", "true"));
        DatabaseAdvisorRuleResultDto rewriteResult = mysqlRewrite.evaluate(mysql(rewrite, JdbcBatching.enabled(25)));
        assertThat(rewriteResult.status()).isEqualTo("SKIPPED");
        assertThat(rewriteResult.sampleViolations().get(0)).contains("cannot be settled");

        DriverEvidence affected = driverEvidence(CONNECTOR_J, conflictingSettings("useAffectedRows", "true"));
        assertThat(affectedRows
                        .evaluate(mysql(affected, JdbcBatching.unknown()))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void aValueTheDriverDoesNotReadAsABooleanIsSkippedNotGuessed() {
        DatabaseAdvisorRuleResultDto result = mysqlRewrite.evaluate(mysql(
                driverEvidence(CONNECTOR_J, urlSettings("rewriteBatchedStatements", "maybe")),
                JdbcBatching.enabled(25)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("does not read as a boolean");
    }

    @Test
    void aGapInAMixedInventoryIsJournaledSoACleanResultCannotHideIt() {
        SchemaSnapshot assessed = driverSchema(
                "assessed", Dialect.POSTGRESQL, driverEvidence(PGJDBC, urlSettings("reWriteBatchedInserts", "true")));
        SchemaSnapshot conflicting = driverSchema(
                "conflicting",
                Dialect.POSTGRESQL,
                driverEvidence(
                        PGJDBC,
                        Map.of(
                                "reWriteBatchedInserts",
                                new DriverSetting("true", DriverSetting.Source.URL, DriverSetting.State.CONFLICTING))));
        DatabaseAdvisorContext context = context(
                List.of(assessed, conflicting),
                Map.of("assessed", JdbcBatching.enabled(30), "conflicting", JdbcBatching.enabled(30)));

        DatabaseAdvisorRuleResultDto result = postgres.evaluate(context);

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.sampleViolations().get(0)).contains("Coverage is incomplete", "conflicting");
        assertThat(context.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-DRIVER-001"));
    }

    @Test
    void aDatasourceHibernateDoesNotUseDoesNotHideAnotherDatasourcesPass() {
        SchemaSnapshot assessed = driverSchema(
                "assessed", Dialect.POSTGRESQL, driverEvidence(PGJDBC, urlSettings("reWriteBatchedInserts", "true")));
        SchemaSnapshot plainJdbc = driverSchema("plainJdbc", Dialect.POSTGRESQL, driverEvidence(PGJDBC, Map.of()));
        DatabaseAdvisorContext context =
                context(List.of(assessed, plainJdbc), Map.of("assessed", JdbcBatching.enabled(30)));

        assertThat(postgres.evaluate(context).status()).isEqualTo("PASS");
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void aDisabledDatasourceInAMixedInventoryIsNotAGap() {
        SchemaSnapshot assessed = driverSchema(
                "assessed", Dialect.POSTGRESQL, driverEvidence(PGJDBC, urlSettings("reWriteBatchedInserts", "true")));
        SchemaSnapshot off = driverSchema("off", Dialect.POSTGRESQL, driverEvidence(PGJDBC, Map.of()));
        DatabaseAdvisorContext context = context(
                List.of(assessed, off), Map.of("assessed", JdbcBatching.enabled(30), "off", JdbcBatching.disabled()));

        assertThat(postgres.evaluate(context).status()).isEqualTo("PASS");
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void aFindingSurvivesAGapOnAnotherDatasource() {
        SchemaSnapshot firing = driverSchema("firing", Dialect.POSTGRESQL, driverEvidence(PGJDBC, Map.of()));
        SchemaSnapshot unknownBatching =
                driverSchema("unknownBatching", Dialect.POSTGRESQL, new DriverEvidence(PGJDBC, "42", Map.of(), false));
        DatabaseAdvisorContext context = context(
                List.of(firing, unknownBatching),
                Map.of("firing", JdbcBatching.enabled(30), "unknownBatching", JdbcBatching.enabled(30)));

        DatabaseAdvisorRuleResultDto result = postgres.evaluate(context);

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("Datasource `firing`");
    }

    @Test
    void anUnreadableDatasourceIsNotEvaluated() {
        DatabaseAdvisorRuleResultDto result =
                postgres.evaluate(context(List.of(SchemaSnapshot.failed("ordersDb", "down")), Map.of()));

        assertThat(result.status()).isEqualTo("SKIPPED");
    }

    @Test
    void aFindingNeverEchoesAnArbitraryConfiguredValue() {
        DatabaseAdvisorRuleResultDto result = mysqlRewrite.evaluate(mysql(
                driverEvidence(CONNECTOR_J, urlSettings("rewriteBatchedStatements", "false")),
                JdbcBatching.enabled(25)));
        assertThat(result.sampleViolations().get(0)).contains("is `false`");

        assertThat(DriverRuleText.shown("false;secretToken=abc")).doesNotContain("secret");
        assertThat(DriverRuleText.shown(null)).doesNotContain("null");
        assertThat(DriverRuleText.shown("")).isEqualTo("empty");
    }

    @Test
    void theRulesAreRegisteredOnceWithTheDriverCategory() {
        List<String> ids = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.definition().id())
                .toList();
        assertThat(ids).contains("DB-DRIVER-001", "DB-DRIVER-002", "DB-DRIVER-003");
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(DatabaseAdvisorRuleRegistry.activeRules().stream()
                        .filter(rule -> rule.definition().id().startsWith("DB-DRIVER"))
                        .map(rule -> rule.definition().category()))
                .containsOnly(DatabaseAdvisorCategory.DRIVER);
        assertThat(DatabaseAdvisorCategory.valueOf("CONNECTION_POOL").label()).isEqualTo("Connection pool");
        assertThat(DatabaseAdvisorCategory.valueOf("SERVER").label()).isEqualTo("Server");
    }
}
