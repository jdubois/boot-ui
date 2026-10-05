package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MySqlIdentifierCaseLiveTests {
    @Container
    static final MySQLContainer MYSQL =
            MySqlLiveFixture.container().withCommand("--performance-schema=ON", "--lower-case-table-names=1");

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(MYSQL);
        try (Connection admin = MySqlLiveFixture.admin(MYSQL);
                Statement sql = admin.createStatement()) {
            sql.execute("GRANT PROCESS ON *.* TO 'reader'@'%'");
            sql.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES'"
                    + " WHERE NAME LIKE 'statement/sql/%' OR NAME='wait/io/table/sql/handler'");
            sql.execute("CREATE TABLE Mixed_Orders (id INT PRIMARY KEY)");
            sql.execute("INSERT INTO Mixed_Orders VALUES (1)");
            sql.execute("INSERT INTO performance_schema.setup_objects"
                    + " (OBJECT_TYPE,OBJECT_SCHEMA,OBJECT_NAME,ENABLED,TIMED)"
                    + " VALUES ('TABLE','BOOTUI_FIXTURE','MIXED_ORDERS','YES','NO')");
        }
    }

    @Test
    void usesMysqlCaseFoldingForObjectRulesAndCatalogToIoJoins() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader");
                Connection connection = pool.getConnection();
                Statement workload = connection.createStatement()) {
            workload.executeQuery("SELECT id FROM MIXED_ORDERS").close();
        }
        try (HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader")) {
            var report = MySqlLiveFixture.service(pool).read().dataSources().get(0);
            assertThat(report.tables())
                    .filteredOn(table -> table.tableName().equalsIgnoreCase("mixed_orders"))
                    .singleElement()
                    .satisfies(table -> {
                        assertThat(table.readOperations()).isNotNull();
                        assertThat(table.totalTimeMs()).isNull();
                    });
            assertThat(report.indexes())
                    .filteredOn(index -> index.tableName().equalsIgnoreCase("mixed_orders"))
                    .isNotEmpty()
                    .allSatisfy(index -> {
                        assertThat(index.readOperations()).isNotNull();
                        assertThat(index.totalTimeMs()).isNull();
                    });
            assertThat(report.sections())
                    .filteredOn(section -> section.id().equals("tables"))
                    .singleElement()
                    .satisfies(section -> assertThat(section.reason()).contains("Table I/O timing"));
        }
    }
}
