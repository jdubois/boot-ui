package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.DataSourceConfiguration;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live pgjdbc coverage for DB-DRIVER-001 and DB-DRIVER-005 to DB-DRIVER-009: the driver's real {@code getDriverName()} and {@code getURL()} reach the
 * rule, a URL that lacks {@code reWriteBatchedInserts} fires it, and a URL that sets it does not. Hibernate batching is
 * supplied through the evidence SPI, since no persistence unit runs here. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresJdbcDriverSettingsLiveTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static DatabaseAdvisorReport scanReport(String urlSuffix) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                postgres.getJdbcUrl() + urlSuffix, postgres.getUsername(), postgres.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        DatabaseAdvisorEvidenceProvider evidence = new DatabaseAdvisorEvidenceProvider() {
            @Override
            public List<DataSourceConfiguration> dataSourceConfigurations() {
                return List.of(new DataSourceConfiguration("primary", null, Map.of(), true));
            }

            @Override
            public Map<String, JdbcBatching> jdbcBatching() {
                return Map.of("primary", JdbcBatching.enabled(30));
            }
        };
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.usingDiscovery(
                        () -> new DatabaseAdvisorDataSourceDiscovery(
                                List.of(new NamedDataSource("primary", source)), List.of()),
                        () -> EntityDiscovery.empty(null),
                        List::of,
                        evidence,
                        Clock.systemUTC())
                .scan();
        assertThat(report.toString()).doesNotContain(postgres.getPassword());
        return report;
    }

    private static Optional<DatabaseAdvisorRuleResultDto> rule(DatabaseAdvisorReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst();
    }

    private static Optional<DatabaseAdvisorRuleResultDto> scan(String urlSuffix) {
        return rule(scanReport(urlSuffix), "DB-DRIVER-001");
    }

    @Test
    void firesWhenTheUrlDoesNotSetReWriteBatchedInserts() {
        assertThat(scan("")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).contains("reWriteBatchedInserts", "batch size 30");
        });
    }

    @Test
    void staysQuietWhenTheUrlSetsIt() {
        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        assertThat(scan(separator + "reWriteBatchedInserts=true")).isEmpty();
    }

    @Test
    void judgesTheSocketTimeoutApplicationNameAndCacheSettingsOfTheRealUrl() {
        DatabaseAdvisorReport bare = scanReport("");

        assertThat(rule(bare, "DB-DRIVER-008"))
                .hasValueSatisfying(result ->
                        assertThat(result.sampleViolations().get(0)).contains("socketTimeout", "unit: seconds"));
        assertThat(rule(bare, "DB-DRIVER-009"))
                .hasValueSatisfying(
                        result -> assertThat(result.sampleViolations().get(0))
                                .contains("ApplicationName", "PostgreSQL JDBC Driver"));
        assertThat(rule(bare, "DB-DRIVER-005")).isEmpty();
        assertThat(rule(bare, "DB-DRIVER-006")).isEmpty();

        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        DatabaseAdvisorReport tuned = scanReport(separator + "socketTimeout=30&ApplicationName=orders"
                + "&autosave=always&preparedStatementCacheQueries=0&logUnclosedConnections=true");
        assertThat(rule(tuned, "DB-DRIVER-008")).isEmpty();
        assertThat(rule(tuned, "DB-DRIVER-009")).isEmpty();
        assertThat(rule(tuned, "DB-DRIVER-005"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .singleElement()
                        .asString()
                        .contains("preparedStatementCacheQueries"));
        assertThat(rule(tuned, "DB-DRIVER-006")).isPresent();
        assertThat(rule(tuned, "DB-DRIVER-007")).isPresent();
    }
}
