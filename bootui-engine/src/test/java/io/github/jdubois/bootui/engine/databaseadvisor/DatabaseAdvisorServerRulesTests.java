package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.setting;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import io.github.jdubois.bootui.engine.support.DetailText;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The fire / no-fire / skip matrix of DB-SERVER-001..007: the database server's own settings. */
class DatabaseAdvisorServerRulesTests {

    private static final String EVIDENCE = "Evidence: effective, as seen by the scan connection.";

    private static SchemaSnapshot postgres(int major, ServerSetting... settings) {
        return postgres(
                major,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SERVER_SETTINGS, List.of(settings), false));
    }

    private static SchemaSnapshot postgres(int major, VendorAugmentation<ServerSetting> settings) {
        return schema(
                Dialect.POSTGRESQL,
                major,
                VendorFindings.builder().add(settings).build());
    }

    private static SchemaSnapshot postgres(
            int major, List<ServerSetting> settings, VendorAugmentation<PostgresInstalledExtension> extension) {
        return schema(
                Dialect.POSTGRESQL,
                major,
                VendorFindings.builder()
                        .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_SERVER_SETTINGS, settings, false))
                        .add(extension)
                        .build());
    }

    private static SchemaSnapshot mysql(Dialect dialect, int major, ServerSetting... settings) {
        return schema(
                dialect,
                major,
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.MYSQL_SERVER_SETTINGS, List.of(settings), false))
                        .build());
    }

    private static SchemaSnapshot schema(Dialect dialect, int major, VendorFindings findings) {
        return new SchemaSnapshot(
                "ordersDb",
                dialect,
                dialect.label(),
                DatabaseVersion.of(major, 0, major + ".0"),
                "LOWER",
                List.of(),
                findings,
                List.of(),
                false,
                null);
    }

    private static DatabaseAdvisorRuleResultDto run(DatabaseAdvisorRule rule, SchemaSnapshot schema) {
        return rule.evaluate(context(List.of(schema)));
    }

    private static String reason(DatabaseAdvisorRuleResultDto result) {
        return result.sampleViolations().get(0);
    }

    // --- the single-setting PostgreSQL rules: one matrix -----------------------------------------------------------

    record Case(
            DatabaseAdvisorRule rule,
            String id,
            String severity,
            String setting,
            int since,
            String flagged,
            String ok) {}

    static Stream<Arguments> singleSettingRules() {
        return Stream.of(
                        new Case(
                                new PostgresIdleInTransactionTimeoutRule(),
                                "DB-SERVER-001",
                                "LOW",
                                "idle_in_transaction_session_timeout",
                                9,
                                "0",
                                "60000"),
                        new Case(
                                new PostgresStatementTimeoutRule(),
                                "DB-SERVER-002",
                                "LOW",
                                "statement_timeout",
                                0,
                                "0",
                                "30000"),
                        new Case(
                                new PostgresAutovacuumDisabledRule(),
                                "DB-SERVER-003",
                                "MEDIUM",
                                "autovacuum",
                                0,
                                "off",
                                "on"),
                        new Case(
                                new PostgresTrackIoTimingRule(),
                                "DB-SERVER-005",
                                "LOW",
                                "track_io_timing",
                                0,
                                "off",
                                "on"),
                        new Case(
                                new PostgresSlowStatementLogRule(),
                                "DB-SERVER-006",
                                "LOW",
                                "log_min_duration_statement",
                                0,
                                "-1",
                                "500"))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("singleSettingRules")
    void aRuleFiresOnlyForTheValueItFlagsAndCarriesItsDefinition(Case c) {
        DatabaseAdvisorRuleResultDto fired = run(c.rule(), postgres(17, setting(c.setting(), c.flagged(), "ms")));

        assertThat(fired.id()).isEqualTo(c.id());
        assertThat(fired.status()).isEqualTo("VIOLATION");
        assertThat(fired.severity()).isEqualTo(c.severity());
        assertThat(fired.category()).isEqualTo("Server");
        assertThat(fired.violationCount()).isEqualTo(1);
        assertThat(fired.learnMoreUrl()).startsWith("https://www.postgresql.org/docs/current/");
        assertThat(fired.recommendation()).contains("PostgreSQL panel");
        assertThat(reason(fired))
                .contains("ordersDb", c.setting(), EVIDENCE)
                .hasSizeLessThanOrEqualTo(DetailText.DEFAULT_MAX_CHARS);

        assertThat(run(c.rule(), postgres(17, setting(c.setting(), c.ok(), "ms")))
                        .status())
                .isEqualTo("PASS");
    }

    @ParameterizedTest
    @MethodSource("singleSettingRules")
    void anUnreadableValueOrAMissingSettingIsSkippedWithAReasonNeverGuessed(Case c) {
        for (String unreadable : List.of("", "abc", "1.5", "unlimited", "-7x")) {
            DatabaseAdvisorRuleResultDto result = run(c.rule(), postgres(17, setting(c.setting(), unreadable)));
            assertThat(result.status())
                    .as(c.id() + " value '" + unreadable + "'")
                    .isEqualTo("SKIPPED");
            assertThat(reason(result)).contains("could not be read");
        }
        // a null value is a setting the server did not report
        assertThat(run(c.rule(), postgres(17, new ServerSetting(c.setting(), null, null)))
                        .status())
                .isEqualTo("SKIPPED");

        DatabaseAdvisorRuleResultDto missing = run(c.rule(), postgres(17));
        assertThat(missing.status()).isEqualTo("SKIPPED");
        assertThat(reason(missing)).contains("did not report `" + c.setting() + "`");
    }

    @ParameterizedTest
    @MethodSource("singleSettingRules")
    void aFailedOrTruncatedSettingsReadIsSkippedNotAPass(Case c) {
        DatabaseAdvisorRuleResultDto failed = run(
                c.rule(),
                postgres(
                        17,
                        VendorAugmentation.failed(VendorFindingKinds.POSTGRES_SERVER_SETTINGS, "permission denied")));
        assertThat(failed.status()).isEqualTo("SKIPPED");
        assertThat(reason(failed)).contains("permission denied");

        DatabaseAdvisorRuleResultDto truncated = run(
                c.rule(),
                postgres(
                        17,
                        VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_SERVER_SETTINGS,
                                List.of(setting(c.setting(), c.flagged())),
                                true)));
        assertThat(truncated.status()).isEqualTo("SKIPPED");
        assertThat(reason(truncated)).contains("truncated");
    }

    @ParameterizedTest
    @MethodSource("singleSettingRules")
    void theRuleIsNotApplicableToOtherDialects(Case c) {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.MARIADB, Dialect.ORACLE, Dialect.GENERIC)) {
            DatabaseAdvisorRuleResultDto result = run(c.rule(), schema(dialect, 8, VendorFindings.EMPTY));
            assertThat(result.status()).as(c.id() + " " + dialect).isEqualTo("SKIPPED");
            assertThat(reason(result)).startsWith("Not applicable:");
        }
    }

    @Test
    void aSettingAnOldServerLacksIsSkippedAsUnsupportedAndAnUnknownVersionIsAGap() {
        DatabaseAdvisorRuleResultDto old = run(new PostgresIdleInTransactionTimeoutRule(), postgres(9));
        assertThat(old.status()).isEqualTo("SKIPPED");
        assertThat(reason(old)).contains("this server version has no `idle_in_transaction_session_timeout`");

        // 9.6 is the first release that has it, so a 9.6 server that does not report it simply did not answer.
        SchemaSnapshot nineSix = schema(
                Dialect.POSTGRESQL,
                9,
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_SERVER_SETTINGS, List.of(), false))
                        .build());
        SchemaSnapshot unknownVersion = new SchemaSnapshot(
                "ordersDb",
                Dialect.POSTGRESQL,
                "PostgreSQL",
                DatabaseVersion.UNKNOWN,
                "LOWER",
                List.of(),
                nineSix.vendorFindings(),
                List.of(),
                false,
                null);
        assertThat(reason(run(new PostgresIdleInTransactionTimeoutRule(), unknownVersion)))
                .contains("did not report");
        // An old server that does have the setting is still judged.
        assertThat(run(
                                new PostgresIdleInTransactionTimeoutRule(),
                                postgres(9, setting("idle_in_transaction_session_timeout", "0")))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void postgresSettingValuesAreJudgedInTheirOwnUnitAndAtTheirBoundaries() {
        // 0 is disabled whatever the unit; any positive value is a timeout, however small
        assertThat(run(new PostgresStatementTimeoutRule(), postgres(17, setting("statement_timeout", "1", "ms")))
                        .status())
                .isEqualTo("PASS");
        // 0 logs every statement and is a deliberate choice; only -1 is "never"
        assertThat(run(
                                new PostgresSlowStatementLogRule(),
                                postgres(17, setting("log_min_duration_statement", "0", "ms")))
                        .status())
                .isEqualTo("PASS");
        assertThat(run(new PostgresSlowStatementLogRule(), postgres(17, setting("log_min_duration_statement", "-2")))
                        .status())
                .isEqualTo("SKIPPED");
        // a boolean is read in the server's own spelling, case-insensitively, and nothing else
        assertThat(run(new PostgresAutovacuumDisabledRule(), postgres(17, setting("autovacuum", "OFF")))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(run(new PostgresAutovacuumDisabledRule(), postgres(17, setting("autovacuum", "maybe")))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @ParameterizedTest
    @MethodSource("singleSettingRules")
    void theFindingIdentityNamesTheDatasourceAndTheSettingNeverTheValue(Case c) {
        DatabaseAdvisorRuleResultDto first = run(c.rule(), postgres(17, setting(c.setting(), c.flagged())));
        assertThat(first.status()).isEqualTo("VIOLATION");
        String identity = AbstractServerSettingRule.identity("ordersDb", c.setting());

        assertThat(identity).isEqualTo("v1:ds=ordersDb|server=" + c.setting());
        assertThat(identity).matches(AdvisorFindingIdentity.VALID);
    }

    // --- DB-SERVER-004 ---------------------------------------------------------------------------------------------

    private final DatabaseAdvisorRule statStatements = new PostgresStatStatementsRule();

    private static VendorAugmentation<PostgresInstalledExtension> extension(boolean created) {
        return VendorAugmentation.available(
                VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION,
                created ? List.of(new PostgresInstalledExtension("pg_stat_statements")) : List.of(),
                false);
    }

    private static List<ServerSetting> libraries(String value) {
        return List.of(setting("shared_preload_libraries", value));
    }

    @Test
    void pgStatStatementsPassesWhenTheLibraryIsPreloadedAndTheExtensionCreated() {
        assertThat(run(statStatements, postgres(17, libraries("pg_stat_statements"), extension(true)))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void pgStatStatementsFiresWhenEitherHalfIsMissingAndSaysWhich() {
        DatabaseAdvisorRuleResultDto notPreloaded =
                run(statStatements, postgres(17, libraries("auto_explain"), extension(true)));
        assertThat(notPreloaded.status()).isEqualTo("VIOLATION");
        assertThat(notPreloaded.id()).isEqualTo("DB-SERVER-004");
        assertThat(notPreloaded.severity()).isEqualTo("LOW");
        assertThat(notPreloaded.category()).isEqualTo("Server");
        assertThat(reason(notPreloaded))
                .contains("pg_stat_statements is not in shared_preload_libraries", EVIDENCE)
                .doesNotContain("not created");

        DatabaseAdvisorRuleResultDto notCreated =
                run(statStatements, postgres(17, libraries("pg_stat_statements"), extension(false)));
        assertThat(notCreated.status()).isEqualTo("VIOLATION");
        assertThat(reason(notCreated))
                .contains("is not created in this database")
                .doesNotContain("shared_preload_libraries");

        DatabaseAdvisorRuleResultDto neither = run(statStatements, postgres(17, libraries(""), extension(false)));
        assertThat(neither.status()).isEqualTo("VIOLATION");
        assertThat(neither.violationCount()).isEqualTo(1);
        assertThat(reason(neither))
                .contains("is not in shared_preload_libraries and is not created in this database")
                .hasSizeLessThanOrEqualTo(DetailText.DEFAULT_MAX_CHARS);
    }

    @Test
    void aPrefixOfAnotherLibraryNameDoesNotCountAsPgStatStatements() {
        assertThat(run(statStatements, postgres(17, libraries("pg_stat_statements_extra"), extension(true)))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void pgStatStatementsIsSkippedWhenEitherReadIsUnknown() {
        // the library list is hidden from this role: no row at all
        DatabaseAdvisorRuleResultDto hidden = run(statStatements, postgres(17, List.of(), extension(true)));
        assertThat(hidden.status()).isEqualTo("SKIPPED");
        assertThat(reason(hidden)).contains("did not report `shared_preload_libraries`");
        // the library list has no value
        assertThat(run(
                                statStatements,
                                postgres(
                                        17,
                                        List.of(new ServerSetting("shared_preload_libraries", null, null)),
                                        extension(true)))
                        .status())
                .isEqualTo("SKIPPED");
        // the extension read failed
        DatabaseAdvisorRuleResultDto failed = run(
                statStatements,
                postgres(
                        17,
                        libraries("pg_stat_statements"),
                        VendorAugmentation.failed(
                                VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION,
                                "The PostgreSQL pg_stat_statements extension could not be read.")));
        assertThat(failed.status()).isEqualTo("SKIPPED");
        assertThat(reason(failed)).contains("could not be read");
        // the extension read was cut short
        assertThat(run(
                                statStatements,
                                postgres(
                                        17,
                                        libraries("pg_stat_statements"),
                                        VendorAugmentation.available(
                                                VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION,
                                                List.of(),
                                                true)))
                        .status())
                .isEqualTo("SKIPPED");
        // the extension read never ran
        assertThat(run(statStatements, postgres(17, setting("shared_preload_libraries", "pg_stat_statements")))
                        .status())
                .isEqualTo("SKIPPED");
        // the settings read failed
        assertThat(run(
                                statStatements,
                                postgres(
                                        17,
                                        VendorAugmentation.failed(
                                                VendorFindingKinds.POSTGRES_SERVER_SETTINGS, "permission denied")))
                        .status())
                .isEqualTo("SKIPPED");
        // an unknown half hides a known-absent other half: the rule stays undecided
        assertThat(run(
                                statStatements,
                                postgres(
                                        17,
                                        libraries("auto_explain"),
                                        VendorAugmentation.failed(
                                                VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION, "refused")))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void pgStatStatementsIsNotApplicableToOtherDialects() {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.MARIADB, Dialect.ORACLE, Dialect.GENERIC)) {
            DatabaseAdvisorRuleResultDto result = run(statStatements, schema(dialect, 8, VendorFindings.EMPTY));
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(reason(result)).startsWith("Not applicable:");
        }
    }

    static Stream<Arguments> sharedPreloadLibraries() {
        return Stream.of(
                Arguments.of("pg_stat_statements", true),
                Arguments.of("pg_stat_statements,auto_explain", true),
                Arguments.of("auto_explain,pg_stat_statements", true),
                Arguments.of("$libdir/pg_stat_statements", true),
                Arguments.of("\"$libdir/pg_stat_statements\"", true),
                Arguments.of("\"pg_stat_statements\"", true),
                Arguments.of("'pg_stat_statements'", true),
                Arguments.of("  auto_explain ,  pg_stat_statements  ", true),
                Arguments.of("auto_explain, \"pg_stat_statements\" , pgaudit", true),
                Arguments.of("pg_stat_statements.so", true),
                Arguments.of("$libdir/pg_stat_statements.so", true),
                Arguments.of("/usr/lib/postgresql/17/lib/pg_stat_statements", true),
                Arguments.of("pg_stat_statements_extra,pg_stat_statements", true),
                Arguments.of("pg_stat_statements_extra", false),
                Arguments.of("my_pg_stat_statements", false),
                Arguments.of("pg_stat_statementsx,xpg_stat_statements", false),
                Arguments.of("pg_stat_statements_extra.so", false),
                Arguments.of("$libdir/pg_stat_statements_extra", false),
                Arguments.of("pg_stat_statements.sql", false),
                Arguments.of("auto_explain,pgaudit", false),
                Arguments.of("\"pg_stat_statements", false),
                Arguments.of("", false),
                Arguments.of("  ", false),
                Arguments.of(",,", false),
                Arguments.of(null, false));
    }

    @ParameterizedTest
    @MethodSource("sharedPreloadLibraries")
    void sharedPreloadLibrariesAreTokenisedNeverMatchedBySubstring(String value, boolean listed) {
        assertThat(PostgresStatStatementsRule.isListed(value, "pg_stat_statements"))
                .as("shared_preload_libraries = %s", value)
                .isEqualTo(listed);
    }

    // --- DB-SERVER-007 ---------------------------------------------------------------------------------------------

    private final DatabaseAdvisorRule performanceSchema = new MySqlPerformanceSchemaDisabledRule();

    @Test
    void performanceSchemaFiresOnlyWhenOffOnMySql() {
        DatabaseAdvisorRuleResultDto off =
                run(performanceSchema, mysql(Dialect.MYSQL, 8, setting("performance_schema", "OFF")));
        assertThat(off.id()).isEqualTo("DB-SERVER-007");
        assertThat(off.status()).isEqualTo("VIOLATION");
        assertThat(off.severity()).isEqualTo("LOW");
        assertThat(off.category()).isEqualTo("Server");
        assertThat(off.recommendation()).contains("MySQL panel");
        assertThat(reason(off)).contains("ordersDb", "performance_schema is OFF", EVIDENCE);

        assertThat(run(performanceSchema, mysql(Dialect.MYSQL, 8, setting("performance_schema", "ON")))
                        .status())
                .isEqualTo("PASS");
        assertThat(run(performanceSchema, mysql(Dialect.MYSQL, 8, setting("performance_schema", "1")))
                        .status())
                .isEqualTo("PASS");
        assertThat(run(performanceSchema, mysql(Dialect.MYSQL, 8, setting("performance_schema", "0")))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void performanceSchemaIsSkippedOnMariaDbWhateverItReports() {
        for (String value : List.of("OFF", "ON")) {
            DatabaseAdvisorRuleResultDto result =
                    run(performanceSchema, mysql(Dialect.MARIADB, 11, setting("performance_schema", value)));
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(reason(result)).contains("MariaDB", "best effort");
        }
    }

    @Test
    void performanceSchemaIsSkippedWhenUnreadableOrAbsentAndNotApplicableElsewhere() {
        assertThat(reason(run(performanceSchema, mysql(Dialect.MYSQL, 8, setting("performance_schema", "weird")))))
                .contains("could not be read");
        assertThat(reason(run(performanceSchema, mysql(Dialect.MYSQL, 8))))
                .contains("did not report `performance_schema`");
        SchemaSnapshot failed = schema(
                Dialect.MYSQL,
                8,
                VendorFindings.builder()
                        .add(VendorAugmentation.failed(VendorFindingKinds.MYSQL_SERVER_SETTINGS, "access denied"))
                        .build());
        assertThat(reason(run(performanceSchema, failed))).contains("access denied");
        for (Dialect dialect : List.of(Dialect.POSTGRESQL, Dialect.ORACLE, Dialect.GENERIC)) {
            DatabaseAdvisorRuleResultDto result = run(performanceSchema, schema(dialect, 8, VendorFindings.EMPTY));
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(reason(result)).startsWith("Not applicable:");
        }
    }

    @Test
    void aMySqlServerBeforeThePerformanceSchemaExistedIsSkippedAsUnsupported() {
        SchemaSnapshot old = new SchemaSnapshot(
                "ordersDb",
                Dialect.MYSQL,
                "MySQL",
                DatabaseVersion.of(5, 1, "5.1.73"),
                "LOWER",
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(VendorFindingKinds.MYSQL_SERVER_SETTINGS, List.of(), false))
                        .build(),
                List.of(),
                false,
                null);
        assertThat(reason(run(performanceSchema, old))).contains("this server version has no `performance_schema`");
    }

    // --- the rule set ------------------------------------------------------------------------------------------------

    @Test
    void theSevenServerRulesAreRegisteredWithOneStableIdAndTheServerCategoryEach() {
        List<DatabaseAdvisorRule> server = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .filter(rule -> rule.definition().id().startsWith("DB-SERVER-"))
                .toList();

        assertThat(server)
                .extracting(rule -> rule.definition().id())
                .containsExactly(
                        "DB-SERVER-001",
                        "DB-SERVER-002",
                        "DB-SERVER-003",
                        "DB-SERVER-004",
                        "DB-SERVER-005",
                        "DB-SERVER-006",
                        "DB-SERVER-007");
        assertThat(server).allSatisfy(rule -> {
            assertThat(rule.definition().category()).isEqualTo(DatabaseAdvisorCategory.SERVER);
            assertThat(rule.definition().learnMoreUrl()).startsWith("https://");
        });
        assertThat(server)
                .extracting(rule -> rule.definition().severity())
                .containsExactly("LOW", "LOW", "MEDIUM", "LOW", "LOW", "LOW", "LOW");
    }

    @Test
    void aMixedInventoryJudgesEachDatasourceAndNamesTheOneThatCouldNotBeRead() {
        SchemaSnapshot readable = postgres(17, setting("autovacuum", "off"));
        SchemaSnapshot hidden = new SchemaSnapshot(
                "reportingDb",
                Dialect.POSTGRESQL,
                "PostgreSQL",
                DatabaseVersion.of(17, 0, "17.0"),
                "LOWER",
                List.of(),
                postgres(17).vendorFindings(),
                List.of(),
                false,
                null);

        DatabaseAdvisorRuleResultDto result =
                new PostgresAutovacuumDisabledRule().evaluate(context(List.of(readable, hidden)));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(reason(result)).contains("ordersDb").doesNotContain("reportingDb");
    }
}
