package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.DataSourceConfiguration;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Scans a live datasource with a given pool, as the pool rules see it: the pool is supplied through the evidence SPI. */
final class PoolServerLiveSupport {

    static final long MINUTE = 60_000L;

    private PoolServerLiveSupport() {}

    /** A HikariCP pool with the given numbers (milliseconds, {@code 0} = disabled). */
    static PoolSettings hikari(
            int maximumPoolSize, int minimumIdle, long idleTimeout, long maxLifetime, long keepalive) {
        return new PoolSettings(
                ConnectionPoolInfo.HIKARI, maximumPoolSize, minimumIdle, idleTimeout, maxLifetime, keepalive, 30_000L);
    }

    static DatabaseAdvisorReport scan(DataSource dataSource, PoolSettings pool, String password) {
        DatabaseAdvisorEvidenceProvider evidence = new DatabaseAdvisorEvidenceProvider() {
            @Override
            public List<DataSourceConfiguration> dataSourceConfigurations() {
                return List.of(new DataSourceConfiguration("primary", pool, Map.of(), true));
            }

            @Override
            public Map<String, JdbcBatching> jdbcBatching() {
                return Map.of();
            }
        };
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.usingDiscovery(
                        () -> new DatabaseAdvisorDataSourceDiscovery(
                                List.of(new NamedDataSource("primary", dataSource)), List.of()),
                        () -> EntityDiscovery.empty(null),
                        List::of,
                        evidence,
                        Clock.systemUTC())
                .scan();
        assertThat(report.toString()).doesNotContain(password);
        return report;
    }

    static Optional<DatabaseAdvisorRuleResultDto> rule(DatabaseAdvisorReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst();
    }

    /**
     * How a rule ended: a reported rule keeps its status, a skipped rule is only a diagnostic, and a clean pass
     * leaves neither.
     */
    static String outcome(DatabaseAdvisorReport report, String id) {
        Optional<DatabaseAdvisorRuleResultDto> result = rule(report, id);
        if (result.isPresent()) {
            return result.get().status();
        }
        return report.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.source().equals(id))
                ? "SKIPPED"
                : "PASS";
    }

    /** Asserts that the server short timeout plus a long pool {@code maxLifetime} is judged, from a real server. */
    static void assertIdleLimitFires(DataSource dataSource, String password, String setting) {
        DatabaseAdvisorReport report = scan(dataSource, hikari(10, 10, 10 * MINUTE, 30 * MINUTE, 0), password);

        assertThat(rule(report, "DB-POOL-001")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0))
                    .contains("maxLifetime 30 min", "server idle limit 1 min", setting);
        });
        assertThat(report.diagnostics())
                .noneMatch(diagnostic -> diagnostic.source().equals("primary")
                        && diagnostic.message().contains("server settings could not be read"));
    }

    /** A maximum lifetime or a keepalive below the server's one-minute limit each cover it. */
    static void assertCoveredPoolStaysQuiet(DataSource dataSource, String password) {
        DatabaseAdvisorReport report = scan(dataSource, hikari(10, 10, 10 * MINUTE, 45_000L, 0), password);
        assertThat(rule(report, "DB-POOL-001")).isEmpty();
        report = scan(dataSource, hikari(10, 10, 10 * MINUTE, 30 * MINUTE, 30_000L), password);
        assertThat(rule(report, "DB-POOL-001")).isEmpty();
    }

    /** A pool far larger than any test server's connection limit exceeds it. */
    static void assertOversizedPoolExceedsTheServer(DataSource dataSource, String password) {
        DatabaseAdvisorReport report =
                scan(dataSource, hikari(100_000, 10, 10 * MINUTE, 30 * MINUTE, 2 * MINUTE), password);
        assertThat(rule(report, "DB-POOL-002")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).contains("maximumPoolSize 100000", "max_connections");
        });
    }
}
