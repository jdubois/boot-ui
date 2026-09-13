package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Connection-free PostgreSQL datasource detection used to decide whether the panel is offered at all. */
class PostgresDataSourceDetectionTests {

    private abstract static class TestDataSource implements DataSource {

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("Detection must never open a connection");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("Detection must never open a connection");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(TestDataSource.class.getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @Test
    void detectsPlainAndWrappedPostgresUrls() {
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:postgresql://localhost:5432/sample"))
                .isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("JDBC:POSTGRESQL://localhost/sample"))
                .isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:aws-wrapper:postgresql://db/sample"))
                .isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:p6spy:postgresql://db/sample"))
                .isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:tc:postgresql:16:///sample"))
                .isTrue();
    }

    @Test
    void rejectsOtherDatabasesAndNonJdbcValues() {
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:h2:mem:sample"))
                .isFalse();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("jdbc:mysql://localhost:3306/sample"))
                .isFalse();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl("postgresql://localhost/sample"))
                .isFalse();
        assertThat(PostgresDataSourceDetection.isPostgresJdbcUrl(null)).isFalse();
    }

    @Test
    void detectsPostgresDbKinds() {
        assertThat(PostgresDataSourceDetection.isPostgresDbKind("postgresql")).isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresDbKind("PostgreSQL")).isTrue();
        assertThat(PostgresDataSourceDetection.isPostgresDbKind("mariadb")).isFalse();
        assertThat(PostgresDataSourceDetection.isPostgresDbKind(null)).isFalse();
    }

    @Test
    void readsTheDeclaredUrlFromEitherAccessorWithoutConnecting() {
        DataSource hikariStyle = new TestDataSource() {
            @SuppressWarnings("unused")
            public String getJdbcUrl() {
                return "jdbc:postgresql://localhost:5432/sample";
            }
        };
        DataSource driverManagerStyle = new TestDataSource() {
            @SuppressWarnings("unused")
            public String getUrl() {
                return "jdbc:h2:mem:sample";
            }
        };

        assertThat(PostgresDataSourceDetection.jdbcUrlOf(hikariStyle))
                .isEqualTo("jdbc:postgresql://localhost:5432/sample");
        assertThat(PostgresDataSourceDetection.jdbcUrlOf(driverManagerStyle)).isEqualTo("jdbc:h2:mem:sample");
    }

    @Test
    void reportsAnUnknownUrlRatherThanGuessing() {
        assertThat(PostgresDataSourceDetection.jdbcUrlOf(new TestDataSource() {}))
                .isNull();
        assertThat(PostgresDataSourceDetection.jdbcUrlOf(new TestDataSource() {
                    @SuppressWarnings("unused")
                    public String getJdbcUrl() {
                        return "   ";
                    }
                }))
                .isNull();
        assertThat(PostgresDataSourceDetection.jdbcUrlOf(new TestDataSource() {
                    @SuppressWarnings("unused")
                    public String getUrl() {
                        throw new IllegalStateException("not configured yet");
                    }
                }))
                .isNull();
        assertThat(PostgresDataSourceDetection.jdbcUrlOf(null)).isNull();
    }
}
