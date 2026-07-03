package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimpleDriverDataSourceTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private static String freshUrl() {
        return "jdbc:h2:mem:simple-driver-test-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    @Test
    void opensAWorkingConnectionWithoutCredentials() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), null, null, null);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void opensAWorkingConnectionWithCredentials() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), "sa", "", null);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void loadsAnExplicitDriverClassWhenProvided() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), "sa", "", "org.h2.Driver");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void throwsForAnUnknownDriverClassName() {
        assertThatThrownBy(() -> new SimpleDriverDataSource(freshUrl(), "sa", "", "not.a.real.Driver"))
                .isInstanceOf(ActivityStoreException.class);
    }

    @Test
    void unwrapsToItselfButNotOtherTypes() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), null, null, null);
        assertThat(dataSource.isWrapperFor(SimpleDriverDataSource.class)).isTrue();
        assertThat(dataSource.unwrap(SimpleDriverDataSource.class)).isSameAs(dataSource);

        assertThat(dataSource.isWrapperFor(String.class)).isFalse();
        assertThatThrownBy(() -> dataSource.unwrap(String.class)).isInstanceOf(SQLException.class);
    }

    @Test
    void getParentLoggerIsUnsupported() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), null, null, null);
        assertThatThrownBy(dataSource::getParentLogger).isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    void loginTimeoutIsSettableAndReadable() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(freshUrl(), null, null, null);
        int previous = dataSource.getLoginTimeout();
        try {
            dataSource.setLoginTimeout(7);
            assertThat(dataSource.getLoginTimeout()).isEqualTo(7);
        } finally {
            // DriverManager's login timeout is process-global, not per-DataSource; restore it so this
            // test does not affect any other test's JDBC connections.
            dataSource.setLoginTimeout(previous);
        }
    }
}
