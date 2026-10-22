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
import org.testcontainers.mysql.MySQLContainer;

/**
 * Live MySQL Connector/J coverage for DB-DRIVER-002 and DB-DRIVER-003. Connector/J's real driver name and URL reach the
 * rules; each property is exercised both absent and set. Hibernate batching is supplied through the evidence SPI, since
 * no persistence unit runs here. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlJdbcDriverSettingsLiveTests {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.6");

    private static DatabaseAdvisorReport scan(String urlSuffix) {
        String url = mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + urlSuffix;
        DriverManagerDataSource source = new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        DatabaseAdvisorEvidenceProvider evidence = new DatabaseAdvisorEvidenceProvider() {
            @Override
            public List<DataSourceConfiguration> dataSourceConfigurations() {
                return List.of(new DataSourceConfiguration("primary", null, Map.of(), true));
            }

            @Override
            public Map<String, JdbcBatching> jdbcBatching() {
                return Map.of("primary", JdbcBatching.enabled(25));
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
        assertThat(report.toString()).doesNotContain(mysql.getPassword());
        return report;
    }

    private static Optional<DatabaseAdvisorRuleResultDto> rule(DatabaseAdvisorReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst();
    }

    @Test
    void firesWhenTheUrlSetsNeitherRewriteBatchedStatementsNorUseAffectedRows() {
        DatabaseAdvisorReport report = scan("connectTimeout=10000");

        assertThat(rule(report, "DB-DRIVER-002")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).contains("rewriteBatchedStatements", "batch size 25");
        });
        assertThat(rule(report, "DB-DRIVER-003")).isEmpty();
    }

    @Test
    void staysQuietForRewriteWhenSetAndFiresForUseAffectedRowsWhenTrue() {
        DatabaseAdvisorReport report = scan("rewriteBatchedStatements=true&useAffectedRows=true");

        assertThat(rule(report, "DB-DRIVER-002")).isEmpty();
        assertThat(rule(report, "DB-DRIVER-003")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).contains("useAffectedRows", "JDBC URL");
        });
    }
}
