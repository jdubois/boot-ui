package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.agroal;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.contextWithPools;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.hikari;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.serverSchema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.setting;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fire / no-fire / skip matrix of DB-POOL-001..004: a pool compared with the limits of its server. */
class DatabaseAdvisorPoolRulesTests {

    private static final long MINUTE = 60_000L;

    private final DatabaseAdvisorRule idle = new PoolIdleLimitRule();
    private final DatabaseAdvisorRule exceeds = new PoolExceedsServerConnectionsRule();
    private final DatabaseAdvisorRule lifetime = new PoolUnlimitedLifetimeRule();
    private final DatabaseAdvisorRule share = new PoolServerShareRule();

    /** A MySQL server whose sessions are closed after ten minutes idle. */
    private static SchemaSnapshot mysql(String waitTimeout, String maxConnections) {
        return serverSchema(
                "ordersDb",
                Dialect.MYSQL,
                8,
                VendorFindingKinds.MYSQL_SERVER_SETTINGS,
                setting("wait_timeout", waitTimeout),
                setting("max_connections", maxConnections));
    }

    private static SchemaSnapshot postgres(int major, ServerSetting... settings) {
        return serverSchema(
                "ordersDb", Dialect.POSTGRESQL, major, VendorFindingKinds.POSTGRES_SERVER_SETTINGS, settings);
    }

    private static DatabaseAdvisorContext with(SchemaSnapshot schema, PoolSettings pool) {
        return contextWithPools(List.of(schema), pool == null ? Map.of() : Map.of("ordersDb", pool));
    }

    private static String reason(DatabaseAdvisorRuleResultDto result) {
        return result.sampleViolations().get(0);
    }

    // --- DB-POOL-001 -----------------------------------------------------------------------------------------------

    @Test
    void idleLimitFiresWhenNothingRetiresOrPingsTheConnectionBeforeTheServerCloses() {
        // wait_timeout 600 s; no lifetime, no keepalive, minimumIdle kept
        DatabaseAdvisorRuleResultDto result = idle.evaluate(with(mysql("600", "151"), hikari(10, 10, 600_000, 0, 0)));

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-POOL-001");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.category()).isEqualTo("Connection pool");
        assertThat(reason(result))
                .contains("ordersDb", "maxLifetime 0 (none)", "keepaliveTime 0 (off)")
                .contains("idle retirement off (minimumIdle 10)", "10 min", "wait_timeout")
                .contains("may hand out a connection the server has already closed", "Evidence: effective")
                .doesNotContain("error");
    }

    @Test
    void idleLimitFiresWhenTheLifetimeIsNotShorterThanTheServerLimit() {
        assertThat(idle.evaluate(with(mysql("600", "151"), hikari(10, 10, 0, 600_000, 0)))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(idle.evaluate(with(mysql("600", "151"), hikari(10, 10, 0, 30 * MINUTE, 0)))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void idleLimitIsCoveredByAShorterLifetimeAKeepaliveOrIdleRetirement() {
        SchemaSnapshot schema = mysql("600", "151");
        // lifetime 9 minutes < 10 minutes
        assertThat(idle.evaluate(with(schema, hikari(10, 10, 0, 9 * MINUTE, 0))).status())
                .isEqualTo("PASS");
        // keepalive 2 minutes (the HikariCP default) < 10 minutes
        assertThat(idle.evaluate(with(schema, hikari(10, 10, 0, 0, 2 * MINUTE))).status())
                .isEqualTo("PASS");
        // minimumIdle 0 and idleTimeout 1 minute + 30 s of scheduling variation < 10 minutes
        assertThat(idle.evaluate(with(schema, hikari(10, 0, MINUTE, 0, 0))).status())
                .isEqualTo("PASS");
    }

    @Test
    void idleRetirementNeedsMinimumIdleZeroAndSchedulingHeadroom() {
        SchemaSnapshot schema = mysql("600", "151");
        // minimumIdle above zero keeps connections idle forever
        assertThat(idle.evaluate(with(schema, hikari(10, 1, MINUTE, 0, 0))).status())
                .isEqualTo("VIOLATION");
        // HikariCP's unset minimumIdle (-1) means the maximum size
        assertThat(idle.evaluate(with(schema, hikari(10, -1, MINUTE, 0, 0))).status())
                .isEqualTo("VIOLATION");
        // idleTimeout 0 never retires
        assertThat(idle.evaluate(with(schema, hikari(10, 0, 0, 0, 0))).status()).isEqualTo("VIOLATION");
        // idleTimeout 9.5 minutes + 30 s of variation reaches the limit: not covered
        assertThat(idle.evaluate(with(schema, hikari(10, 0, 9 * MINUTE + 30_000, 0, 0)))
                        .status())
                .isEqualTo("VIOLATION");
        // one millisecond less is covered
        assertThat(idle.evaluate(with(schema, hikari(10, 0, 9 * MINUTE + 29_999, 0, 0)))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void anUnreadablePoolSettingNeverHidesOrCreatesAFinding() {
        SchemaSnapshot schema = mysql("600", "151");
        PoolSettings unknownLifetime = new PoolSettings("HikariCP", 10, 10, 0L, null, 0L, 30_000L);
        // nothing covers and one defence is unknown: undecided
        DatabaseAdvisorRuleResultDto undecided = idle.evaluate(with(schema, unknownLifetime));
        assertThat(undecided.status()).isEqualTo("SKIPPED");
        assertThat(reason(undecided)).contains("could not be read");
        // a defence that is known to cover settles it despite another unknown
        PoolSettings covered = new PoolSettings("HikariCP", 10, 10, 0L, null, 2 * MINUTE, 30_000L);
        assertThat(idle.evaluate(with(schema, covered)).status()).isEqualTo("PASS");
    }

    @Test
    void postgresUsesIdleSessionTimeoutInItsUnitAndIgnoresItWhenDisabled() {
        PoolSettings pool = hikari(10, 10, 0, 0, 0);
        // 5 min stored as 300000 ms
        assertThat(idle.evaluate(with(postgres(16, setting("idle_session_timeout", "300000", "ms")), pool))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(reason(idle.evaluate(with(postgres(16, setting("idle_session_timeout", "5", "min")), pool))))
                .contains("5 min", "idle_session_timeout");
        // 0 = disabled: nothing to compare with, no finding
        assertThat(idle.evaluate(with(postgres(16, setting("idle_session_timeout", "0", "ms")), pool))
                        .status())
                .isEqualTo("PASS");
        // a unit that is not known is unknown, not guessed
        DatabaseAdvisorRuleResultDto unknownUnit =
                idle.evaluate(with(postgres(16, setting("idle_session_timeout", "5", "furlong")), pool));
        assertThat(unknownUnit.status()).isEqualTo("SKIPPED");
    }

    @Test
    void postgres13HasNoIdleSessionTimeoutAndNewerServersThatOmitItAreUnknown() {
        PoolSettings pool = hikari(10, 10, 0, 0, 0);
        DatabaseAdvisorRuleResultDto old = idle.evaluate(with(postgres(13, setting("max_connections", "100")), pool));
        assertThat(old.status()).isEqualTo("SKIPPED");
        assertThat(reason(old)).contains("has no `idle_session_timeout`", "PostgreSQL 14");

        DatabaseAdvisorRuleResultDto hidden =
                idle.evaluate(with(postgres(16, setting("max_connections", "100")), pool));
        assertThat(hidden.status()).isEqualTo("SKIPPED");
        assertThat(reason(hidden)).contains("did not report `idle_session_timeout`");
    }

    @Test
    void idleLimitIsSkippedForAgroalWithItsReason() {
        DatabaseAdvisorRuleResultDto result = idle.evaluate(with(mysql("600", "151"), agroal(10, 10, 0, 0)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(reason(result)).contains("Agroal", "neither a keepalive nor a validation setting");
    }

    @Test
    void idleLimitIsSkippedWithTheSpecificReasonWhenThePoolOrServerIsUnknown() {
        assertThat(reason(idle.evaluate(with(mysql("600", "151"), null))))
                .contains("no HikariCP or Agroal connection pool could be attributed");
        PoolSettings dbcp = new PoolSettings("DBCP2", 10, 10, 0L, 0L, 0L, 0L);
        assertThat(reason(idle.evaluate(with(mysql("600", "151"), dbcp)))).contains("not HikariCP or Agroal");

        SchemaSnapshot unreadable = serverSchema(
                "ordersDb",
                Dialect.MYSQL,
                8,
                VendorAugmentation.failed(VendorFindingKinds.MYSQL_SERVER_SETTINGS, "denied"));
        DatabaseAdvisorRuleResultDto failed = idle.evaluate(with(unreadable, hikari(10, 10, 0, 0, 0)));
        assertThat(failed.status()).isEqualTo("SKIPPED");
        assertThat(reason(failed)).contains("denied");

        SchemaSnapshot missing = serverSchema(
                "ordersDb",
                Dialect.MYSQL,
                8,
                VendorFindingKinds.MYSQL_SERVER_SETTINGS,
                setting("max_connections", "10"));
        assertThat(reason(idle.evaluate(with(missing, hikari(10, 10, 0, 0, 0)))))
                .contains("did not report `wait_timeout`");
        assertThat(reason(idle.evaluate(with(mysql("0", "151"), hikari(10, 10, 0, 0, 0)))))
                .contains("could not be read");
    }

    @Test
    void idleLimitIsMatchedByExactDatasourceNameOnly() {
        DatabaseAdvisorContext context =
                contextWithPools(List.of(mysql("600", "151")), Map.of("OrdersDb", hikari(10, 10, 0, 0, 0)));
        assertThat(idle.evaluate(context).status()).isEqualTo("SKIPPED");
    }

    @Test
    void poolRulesIgnoreDialectsTheyDoNotJudge() {
        SchemaSnapshot h2 = DatabaseAdvisorFixtures.schema("ordersDb", Dialect.GENERIC, List.of());
        for (DatabaseAdvisorRule rule : List.of(idle, exceeds, lifetime, share)) {
            DatabaseAdvisorRuleResultDto result = rule.evaluate(with(h2, hikari(10, 10, 0, 0, 0)));
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(reason(result)).startsWith("Not applicable");
        }
    }

    @Test
    void idleLimitFindingsAreKeyedOnTheDatasourceAndNotOnValues() {
        DatabaseAdvisorRuleResultDto first = idle.evaluate(with(mysql("600", "151"), hikari(10, 10, 0, 0, 0)));
        DatabaseAdvisorRuleResultDto second = idle.evaluate(with(mysql("700", "151"), hikari(12, 12, 0, 0, 0)));
        assertThat(first.status()).isEqualTo("VIOLATION");
        assertThat(second.status()).isEqualTo("VIOLATION");
        assertThat(PoolIdleLimitRule.identity("ordersDb"))
                .isEqualTo(DatabaseIdentities.of("ordersDb", "pool=idle-limit"));
    }

    // --- DB-POOL-002 / DB-POOL-004 ---------------------------------------------------------------------------------

    @Test
    void aPoolLargerThanTheUsableConnectionsFiresTheCapacityRuleAndNotTheShareRule() {
        DatabaseAdvisorContext context = with(mysql("600", "50"), hikari(60, 10, 0, 0, 0));

        DatabaseAdvisorRuleResultDto result = exceeds.evaluate(context);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-POOL-002");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(reason(result))
                .contains("maximumPoolSize 60", "50 connections", "max_connections 50", "cannot fill")
                .contains("unless a proxy multiplexes connections");
        assertThat(share.evaluate(context).status()).isEqualTo("PASS");
    }

    @Test
    void aPoolOverHalfTheUsableConnectionsFiresTheShareHeuristicAtLowSeverity() {
        DatabaseAdvisorContext context = with(mysql("600", "100"), hikari(51, 10, 0, 0, 0));

        DatabaseAdvisorRuleResultDto result = share.evaluate(context);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-POOL-004");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(reason(result)).contains("more than 50%", "Heuristic", "second instance");
        assertThat(exceeds.evaluate(context).status()).isEqualTo("PASS");
    }

    @Test
    void thePoolShareBoundariesUseExactIntegerArithmetic() {
        // equal to the usable connections: fits, but is heavy
        assertThat(exceeds.evaluate(with(mysql("600", "100"), hikari(100, 10, 0, 0, 0)))
                        .status())
                .isEqualTo("PASS");
        assertThat(share.evaluate(with(mysql("600", "100"), hikari(100, 10, 0, 0, 0)))
                        .status())
                .isEqualTo("VIOLATION");
        // one over exceeds
        assertThat(exceeds.evaluate(with(mysql("600", "100"), hikari(101, 10, 0, 0, 0)))
                        .status())
                .isEqualTo("VIOLATION");
        // exactly 50 % is not above 50 %
        assertThat(share.evaluate(with(mysql("600", "100"), hikari(50, 10, 0, 0, 0)))
                        .status())
                .isEqualTo("PASS");
        // an odd usable count: 49 of 97 is 50.5 %
        SchemaSnapshot odd = postgres(
                16,
                setting("max_connections", "100"),
                setting("superuser_reserved_connections", "3"),
                setting("reserved_connections", "0"));
        assertThat(share.evaluate(with(odd, hikari(49, 10, 0, 0, 0))).status()).isEqualTo("VIOLATION");
        assertThat(share.evaluate(with(odd, hikari(48, 10, 0, 0, 0))).status()).isEqualTo("PASS");
    }

    @Test
    void postgresUsableConnectionsSubtractTheReservedOnesWhenTheServerHasThem() {
        PoolSettings pool = hikari(98, 10, 0, 0, 0);
        SchemaSnapshot v16 = postgres(
                16,
                setting("max_connections", "100"),
                setting("superuser_reserved_connections", "3"),
                setting("reserved_connections", "2"));
        DatabaseAdvisorRuleResultDto result = exceeds.evaluate(with(v16, pool));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(reason(result))
                .contains("95 connections", "max_connections 100", "superuser_reserved_connections 3")
                .contains("reserved_connections 2");

        // PostgreSQL 15 has no reserved_connections: its absence is normal
        SchemaSnapshot v15 =
                postgres(15, setting("max_connections", "100"), setting("superuser_reserved_connections", "3"));
        assertThat(exceeds.evaluate(with(v15, hikari(98, 10, 0, 0, 0))).status())
                .isEqualTo("VIOLATION");
        assertThat(exceeds.evaluate(with(v15, hikari(97, 10, 0, 0, 0))).status())
                .isEqualTo("PASS");
    }

    @Test
    void aMissingReservedConnectionsSettingIsUnknownOnPostgres16OrAnUnknownVersion() {
        SchemaSnapshot v16 =
                postgres(16, setting("max_connections", "100"), setting("superuser_reserved_connections", "3"));
        DatabaseAdvisorRuleResultDto result = exceeds.evaluate(with(v16, hikari(98, 10, 0, 0, 0)));
        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(reason(result)).contains("did not report `reserved_connections`");

        SchemaSnapshot noSuperuser = postgres(16, setting("max_connections", "100"));
        assertThat(reason(exceeds.evaluate(with(noSuperuser, hikari(98, 10, 0, 0, 0)))))
                .contains("`superuser_reserved_connections`");
    }

    @Test
    void theCapacityRulesNeedAReadablePoolSizeAndServerLimit() {
        assertThat(reason(exceeds.evaluate(with(mysql("600", "100"), hikari(-1, 10, 0, 0, 0)))))
                .contains("maximum size could not be read");
        assertThat(reason(exceeds.evaluate(with(mysql("600", "many"), hikari(10, 10, 0, 0, 0)))))
                .contains("`max_connections`");
        assertThat(reason(share.evaluate(with(mysql("600", "100"), null)))).contains("no HikariCP or Agroal");
        SchemaSnapshot truncated = serverSchema(
                "ordersDb",
                Dialect.MYSQL,
                8,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_SERVER_SETTINGS, List.of(setting("max_connections", "10")), true));
        assertThat(reason(exceeds.evaluate(with(truncated, hikari(50, 10, 0, 0, 0)))))
                .contains("truncated");
    }

    @Test
    void theCapacityRulesWorkForAgroalToo() {
        assertThat(exceeds.evaluate(with(mysql("600", "40"), agroal(50, 0, 0, 0)))
                        .status())
                .isEqualTo("VIOLATION");
        // Agroal's default maximum size of 50 against a stock PostgreSQL's 97 usable connections
        SchemaSnapshot stock = postgres(
                16,
                setting("max_connections", "100"),
                setting("superuser_reserved_connections", "3"),
                setting("reserved_connections", "0"));
        assertThat(share.evaluate(with(stock, agroal(50, 0, 0, 0))).status()).isEqualTo("VIOLATION");
    }

    // --- DB-POOL-003 -----------------------------------------------------------------------------------------------

    @Test
    void anUnlimitedLifetimeIsReportedWhenTheIdleLimitRuleDoesNotOwnTheDatasource() {
        // a keepalive covers the server limit, so DB-POOL-001 stays quiet and DB-POOL-003 speaks
        DatabaseAdvisorContext context = with(mysql("600", "151"), hikari(10, 10, 0, 0, 2 * MINUTE));

        assertThat(idle.evaluate(context).status()).isEqualTo("PASS");
        DatabaseAdvisorRuleResultDto result = lifetime.evaluate(context);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.id()).isEqualTo("DB-POOL-003");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(reason(result)).contains("maxLifetime is 0", "never retired for age");
    }

    @Test
    void anUnlimitedLifetimeIsNotReportedTwiceWhenTheIdleLimitRuleFires() {
        DatabaseAdvisorContext context = with(mysql("600", "151"), hikari(10, 10, 0, 0, 0));

        assertThat(idle.evaluate(context).status()).isEqualTo("VIOLATION");
        assertThat(lifetime.evaluate(context).status()).isEqualTo("PASS");
    }

    @Test
    void aLimitedLifetimeIsNotReported() {
        assertThat(lifetime.evaluate(with(mysql("600", "151"), hikari(10, 10, 0, 30 * MINUTE, 0)))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void anUnlimitedLifetimeOnAgroalIsReportedBecauseTheIdleLimitRuleCannotJudgeIt() {
        DatabaseAdvisorContext context = with(mysql("600", "151"), agroal(10, 0, 0, 0));

        assertThat(idle.evaluate(context).status()).isEqualTo("SKIPPED");
        DatabaseAdvisorRuleResultDto result = lifetime.evaluate(context);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(reason(result)).contains("Agroal default", "quarkus.datasource.jdbc.max-lifetime");
    }

    @Test
    void anUnlimitedLifetimeIsReportedWhenTheServerHasNoIdleLimitToCompareWith() {
        SchemaSnapshot noLimit = postgres(16, setting("idle_session_timeout", "0", "ms"));
        assertThat(lifetime.evaluate(with(noLimit, hikari(10, 10, 0, 0, 0))).status())
                .isEqualTo("VIOLATION");
        SchemaSnapshot old = postgres(13, setting("max_connections", "100"));
        assertThat(lifetime.evaluate(with(old, hikari(10, 10, 0, 0, 0))).status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void anUnlimitedLifetimeIsUndecidedWhileOwnershipByTheIdleLimitRuleIsUnsettled() {
        SchemaSnapshot unreadable = serverSchema(
                "ordersDb",
                Dialect.MYSQL,
                8,
                VendorAugmentation.failed(VendorFindingKinds.MYSQL_SERVER_SETTINGS, "denied"));
        DatabaseAdvisorRuleResultDto result = lifetime.evaluate(with(unreadable, hikari(10, 10, 0, 0, 0)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(reason(result)).contains("DB-POOL-001", "could not be settled", "denied");
    }

    @Test
    void anUnreadableLifetimeIsNotReportedAndAnUnattributedPoolIsSkipped() {
        PoolSettings unknown = new PoolSettings("HikariCP", 10, 10, 0L, null, 0L, 30_000L);
        assertThat(reason(lifetime.evaluate(with(mysql("600", "151"), unknown))))
                .contains("`maxLifetime` could not");
        assertThat(reason(lifetime.evaluate(with(mysql("600", "151"), null)))).contains("no HikariCP or Agroal");
    }

    @Test
    void theHikariDefaultsDoNotFireAnyPoolRuleOnAStockServer() {
        // HikariCP defaults: lifetime 30 min, idleTimeout 10 min, keepalive 2 min, pool of 10
        PoolSettings defaults = hikari(10, 10, 10 * MINUTE, 30 * MINUTE, 2 * MINUTE);
        DatabaseAdvisorContext mysqlStock = with(mysql("28800", "151"), defaults);
        DatabaseAdvisorContext postgresStock = with(
                postgres(
                        16,
                        setting("idle_session_timeout", "0", "ms"),
                        setting("max_connections", "100"),
                        setting("superuser_reserved_connections", "3"),
                        setting("reserved_connections", "0")),
                defaults);
        for (DatabaseAdvisorContext context : List.of(mysqlStock, postgresStock)) {
            for (DatabaseAdvisorRule rule : List.of(idle, exceeds, lifetime, share)) {
                assertThat(rule.evaluate(context).status())
                        .as(rule.definition().id())
                        .isEqualTo("PASS");
            }
        }
    }
}
