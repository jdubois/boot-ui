package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real statistics rows, including functional keys and IS_VISIBLE, rather than MariaDB's catalog shape. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlIndexComparisonLiveTests {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.6");

    @Test
    void comparesOnlyExactOrdinaryDefinitionsAndTheProvenPrimaryKeyBackingIndex() throws Exception {
        DriverManagerDataSource source =
                new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table index_comparison (id bigint not null primary key, "
                    + "a varchar(64) not null, b varchar(64) not null, "
                    + "generated_a varchar(64) as (upper(a)) stored, unique index uq_id (id), "
                    + "index ix_ab (a, b), index ix_same (a, b), index ix_reordered (b, a), "
                    + "index ix_desc (a, b desc), index ix_prefix (a(10), b), "
                    + "index ix_generated (generated_a, b), index ix_expression ((lower(a)), b), "
                    + "index ix_invisible (a, b) invisible) engine = InnoDB");
        }

        DatabaseAdvisorReport report = DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", source)),
                        () -> EntityDiscovery.absent(null),
                        Clock.systemUTC())
                .scan();

        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).as("%s", report.diagnostics()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("MySQL");
        });
        assertThat(report.rulesErrored()).isZero();
        assertThat(rule(report, "DB-SCHEMA-003").sampleViolations())
                .singleElement()
                .asString()
                .contains("index_comparison", "ix_ab and ix_same")
                .doesNotContain(
                        "ix_reordered", "ix_desc", "ix_prefix", "ix_generated", "ix_expression", "ix_invisible");
        assertThat(rule(report, "DB-SCHEMA-005").sampleViolations())
                .singleElement()
                .asString()
                .contains("index_comparison", "uq_id", "PRIMARY");
        assertThat(report.diagnostics())
                .noneMatch(
                        diagnostic -> List.of("DB-SCHEMA-003", "DB-SCHEMA-005").contains(diagnostic.source()));
    }

    @Test
    void timestampExpressionDefaultsDoNotHideOrdinaryIndexDuplicates() throws Exception {
        DriverManagerDataSource source =
                new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            try {
                statement.execute("create table timestamp_comparison (id bigint not null primary key, "
                        + "created timestamp not null default current_timestamp, "
                        + "index ix_created_first (created), index ix_created_second (created)) engine = InnoDB");
                try (var rows = statement.executeQuery(
                        "select extra from information_schema.columns where table_schema = database() "
                                + "and table_name = 'timestamp_comparison' and column_name = 'created'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("extra")).contains("DEFAULT_GENERATED");
                }
                DatabaseAdvisorReport report = DatabaseAdvisorScanner.using(
                                () -> List.of(new NamedDataSource("primary", source)),
                                () -> EntityDiscovery.absent(null),
                                Clock.systemUTC())
                        .scan();
                assertThat(rule(report, "DB-SCHEMA-003").sampleViolations())
                        .anySatisfy(detail -> assertThat(detail)
                                .contains("timestamp_comparison", "ix_created_first and ix_created_second"));
                assertThat(report.diagnostics())
                        .noneMatch(diagnostic -> diagnostic.source().equals("DB-SCHEMA-003")
                                && diagnostic.message().contains("timestamp_comparison"));
            } finally {
                statement.execute("drop table if exists timestamp_comparison");
            }
        }
    }

    private static DatabaseAdvisorRuleResultDto rule(DatabaseAdvisorReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing finding " + id + ": " + report.diagnostics()));
    }
}
