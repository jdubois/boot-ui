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
 * Live pgjdbc coverage for DB-DRIVER-001: the driver's real {@code getDriverName()} and {@code getURL()} reach the
 * rule, a URL that lacks {@code reWriteBatchedInserts} fires it, and a URL that sets it does not. Hibernate batching is
 * supplied through the evidence SPI, since no persistence unit runs here. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresJdbcDriverSettingsLiveTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static Optional<DatabaseAdvisorRuleResultDto> scan(String urlSuffix) {
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
        return report.results().stream()
                .filter(result -> result.id().equals("DB-DRIVER-001"))
                .findFirst();
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
}
