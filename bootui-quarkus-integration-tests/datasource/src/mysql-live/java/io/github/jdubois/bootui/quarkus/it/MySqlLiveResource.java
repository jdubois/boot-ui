package io.github.jdubois.bootui.quarkus.it;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Opt-in disposable server; compiled only by -Pmysql-live. A missing Docker daemon is a test failure,
 * never a skip. Administrative credentials are used only to prepare this synthetic fixture.
 */
public final class MySqlLiveResource implements QuarkusTestResourceLifecycleManager {

    public static final String IMAGE = "mysql:8.4.6";
    private MySQLContainer mysql;

    @Override
    public Map<String, String> start() {
        mysql = new MySQLContainer(IMAGE)
                .withDatabaseName("bootui_mysql")
                .withUsername("bootui_reader")
                .withPassword("disposable-fixture-only");
        try {
            mysql.start();
            prepareFixture();
            Map<String, String> config = new LinkedHashMap<>();
            config.put("quarkus.datasource.mysql.db-kind", "mysql");
            config.put("quarkus.datasource.mysql.jdbc.url", mysql.getJdbcUrl());
            config.put("quarkus.datasource.mysql.username", mysql.getUsername());
            config.put("quarkus.datasource.mysql.password", mysql.getPassword());
            config.put("quarkus.datasource.mysql.devservices.enabled", "false");
            config.put("quarkus.datasource.mysql.jdbc.min-size", "0");
            config.put("quarkus.datasource.mysql.jdbc.max-size", "1");
            config.put("quarkus.datasource.mysql.jdbc.metrics.enabled", "true");
            config.put("quarkus.datasource.mysql.jdbc.acquisition-timeout", "3S");
            config.put("bootui.mcp.enabled", "ON");
            config.put("bootui.mcp.max-payload-bytes", "1048576");
            return config;
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Required MySQL live fixture could not start or initialize", failure);
        }
    }

    private void prepareFixture() throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fixture_products (id BIGINT PRIMARY KEY, category VARCHAR(30), "
                    + "quantity INT, INDEX fixture_category (category)) ENGINE=InnoDB");
            statement.execute("INSERT INTO fixture_products VALUES (1, 'synthetic', 3), (2, 'demo', 7)");
            statement.execute("REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'bootui_reader'@'%'");
            statement.execute("GRANT SELECT ON bootui_mysql.* TO 'bootui_reader'@'%'");
            for (String table : List.of(
                    "global_status",
                    "setup_consumers",
                    "setup_instruments",
                    "setup_objects",
                    "threads",
                    "events_statements_summary_by_digest",
                    "table_io_waits_summary_by_table",
                    "table_io_waits_summary_by_index_usage",
                    "data_locks",
                    "data_lock_waits",
                    "metadata_locks",
                    "replication_connection_status",
                    "replication_applier_status",
                    "replication_applier_status_by_coordinator",
                    "replication_applier_status_by_worker")) {
                statement.execute("GRANT SELECT ON performance_schema." + table + " TO 'bootui_reader'@'%'");
            }
        }
        // Deliberate fixture workload, not BootUI collection or panel loading.
        try (var connection =
                        DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                var statement =
                        connection.prepareStatement("SELECT quantity FROM fixture_products WHERE category = ?")) {
            statement.setString(1, "synthetic");
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Synthetic MySQL workload did not return its fixture");
                }
            }
        }
    }

    @Override
    public void stop() {
        if (mysql != null) {
            mysql.stop();
            mysql = null;
        }
    }
}
