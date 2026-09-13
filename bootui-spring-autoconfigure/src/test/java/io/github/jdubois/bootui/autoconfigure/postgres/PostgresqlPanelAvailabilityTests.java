package io.github.jdubois.bootui.autoconfigure.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Honest availability of the PostgreSQL panel. The panel reads PostgreSQL's own catalog and statistics views,
 * so it is offered only when a PostgreSQL datasource is configured; the decision is taken from the declared
 * JDBC URL, without opening a connection, and a datasource that declares no readable URL is never claimed to
 * be something it is not.
 */
class PostgresqlPanelAvailabilityTests {

    /** A datasource that declares its URL the way a pool does, with no connection behind it. */
    private static final class UrlDataSource implements DataSource {

        private final String jdbcUrl;

        private UrlDataSource(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("Availability must never open a connection");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("Availability must never open a connection");
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
            return Logger.getLogger(UrlDataSource.class.getName());
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

    private static DataSource declaring(String jdbcUrl) {
        return new UrlDataSource(jdbcUrl);
    }

    private static PanelDto postgresqlPanel(GenericApplicationContext context) {
        return new PanelsController(context, context.getEnvironment(), new BootUiProperties())
                .panels().panels().stream()
                        .filter(panel -> panel.id().equals(BootUiPanels.POSTGRESQL))
                        .findFirst()
                        .orElseThrow();
    }

    private static PanelDto panelWith(DataSource dataSource) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.refresh();
            return postgresqlPanel(context);
        }
    }

    @Test
    void unavailableWithTheHonestReasonWhenNoDataSourceIsPresent() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            PanelDto panel = postgresqlPanel(context);
            assertThat(panel.available()).isFalse();
            assertThat(panel.unavailableReason()).isEqualTo("No DataSource bean is available");
        }
    }

    @Test
    void unavailableWhenTheConfiguredDataSourceIsNotPostgres() {
        PanelDto panel = panelWith(declaring("jdbc:h2:mem:sample"));

        assertThat(panel.available()).isFalse();
        assertThat(panel.unavailableReason()).isEqualTo("No PostgreSQL datasource is configured");
    }

    @Test
    void availableWhenAPostgresDataSourceIsConfigured() {
        assertThat(panelWith(declaring("jdbc:postgresql://localhost:5432/sample"))
                        .available())
                .isTrue();
    }

    @Test
    void availableBehindAWrappingDriver() {
        assertThat(panelWith(declaring("jdbc:aws-wrapper:postgresql://db.example:5432/sample"))
                        .available())
                .isTrue();
    }

    @Test
    void availableWhenTheDataSourceDeclaresNoReadableUrlAndTheDriverIsPresent() {
        assertThat(panelWith(Mockito.mock(DataSource.class)).available()).isTrue();
    }
}
