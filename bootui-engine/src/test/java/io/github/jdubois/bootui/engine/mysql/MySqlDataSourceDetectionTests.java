package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MySqlDataSourceDetectionTests {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://local/db",
                "jdbc:mysql:loadbalance://a,b/db",
                "jdbc:mysql:replication://a,b/db",
                "jdbc:mysql+srv://cluster/db",
                "jdbc:mysql+srv:loadbalance://cluster/db",
                "jdbc:log4jdbc:mysql://a/db",
                "jdbc:p6spy:mysql://a/db",
                "jdbc:aws-wrapper:mysql://a/db",
                "jdbc:otel:mysql://a/db",
                "jdbc:aws-wrapper:p6spy:mysql://a/db",
                "JDBC:MySQL://a/db",
                "  jdbc:mysql://a/db  ",
                "jdbc:tc:mysql:8.4.6:///db"
            })
    void recognizesOnlyDeclaredMySqlProtocol(String url) {
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mariadb://local/db",
                "jdbc:postgresql://local/mysql",
                "jdbc:mysqlish://a",
                "http://mysql",
                "jdbc:postgresql://a/db?x=:mysql:",
                "jdbc:mariadb:mysql://a/db"
            })
    void rejectsOtherVendorsAndAddressSegments(String url) {
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl(url)).isFalse();
    }

    /**
     * A vendor that has no {@code //} authority puts its database name and its parameters in the same
     * colon-separated syntax as the sub-protocol. Reading those as a driver declaration offered the MySQL panel
     * against an H2 or Oracle database, where every statement it issues is invalid.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:h2:mem:mysql",
                "jdbc:h2:mem:mysql;DB_CLOSE_DELAY=-1",
                "jdbc:h2:mem:test;MODE=mysql",
                "jdbc:h2:file:/var/data/mysql",
                "jdbc:p6spy:h2:mem:mysql",
                "jdbc:oracle:thin:@host:1521:mysql",
                "jdbc:sqlserver://host;databaseName=mysql",
                "jdbc:derby:mysql;create=true",
                "jdbc:unknown:mysql-compatible:db"
            })
    void rejectsDatabaseNamesAndParametersThatOnlyMentionMySql(String url) {
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl(url)).isFalse();
    }

    @Test
    void unknownDatasourceDoesNotConnect() {
        DataSource source = mock(DataSource.class);
        assertThat(MySqlDataSourceDetection.jdbcUrlOf(source)).isNull();
        verifyNoInteractions(source);
        assertThat(MySqlDataSourceDetection.isMySqlDbKind("mysql")).isTrue();
        assertThat(MySqlDataSourceDetection.isMySqlDbKind("mariadb")).isFalse();
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl(null)).isFalse();
    }
}
