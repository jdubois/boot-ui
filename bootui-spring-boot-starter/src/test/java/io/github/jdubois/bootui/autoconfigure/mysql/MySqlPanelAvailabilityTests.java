package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.core.dto.PanelDto;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

class MySqlPanelAvailabilityTests {

    public static final class DeclaredSource extends AbstractDataSource {
        private final String jdbcUrl;

        DeclaredSource(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        @Override
        public Connection getConnection() {
            throw new AssertionError("Manifest lookup must never connect");
        }

        @Override
        public Connection getConnection(String user, String password) {
            return getConnection();
        }
    }

    private static PanelDto panel(GenericApplicationContext context) {
        return new PanelsController(context, context.getEnvironment(), new BootUiProperties())
                .panels().panels().stream()
                        .filter(value -> value.id().equals("mysql"))
                        .findFirst()
                        .orElseThrow();
    }

    private static PanelDto panel(DataSource source) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> source);
            context.refresh();
            return panel(context);
        }
    }

    @Test
    void onlyMysqlDeclarationsAreAccepted() {
        for (String url : new String[] {
            "jdbc:mysql://localhost/app",
            "jdbc:mysql:replication://localhost/app",
            "jdbc:aws-wrapper:mysql://localhost/app"
        }) {
            assertThat(panel(new DeclaredSource(url)).available()).as(url).isTrue();
        }
        for (String url :
                new String[] {"jdbc:h2:mem:app", "jdbc:postgresql://localhost/app", "jdbc:mariadb://localhost/app"}) {
            assertThat(panel(new DeclaredSource(url)).available()).as(url).isFalse();
        }
    }

    @Test
    void missingDatasourceHasAnExplicitJdbcRequirement() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(panel(context).unavailableReason()).contains("JDBC DataSource", "reactive");
        }
    }

    @Test
    void sidebarDoesNotInstantiateALazyDatasourceForAnyDatabasePanel() {
        AtomicInteger creations = new AtomicInteger();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(
                    "lazyMysql",
                    DataSource.class,
                    () -> {
                        creations.incrementAndGet();
                        return new DeclaredSource("jdbc:mysql://localhost/app");
                    },
                    definition -> definition.setLazyInit(true));
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "mysql-test", Map.of("spring.datasource.url", "jdbc:mysql://localhost/app")));
            context.refresh();
            assertThat(panel(context).available()).isTrue();
            assertThat(creations).hasValue(0);
        }
    }

    @Test
    void alreadyResolvedRoutingTargetsAndLazyConnectionWrappersAreInspectedWithoutConnecting() {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                throw new AssertionError("Availability must not resolve a request routing key");
            }
        };
        routing.setTargetDataSources(Map.of(
                "other", new DeclaredSource("jdbc:h2:mem:other"),
                "mysql", new DeclaredSource("jdbc:mysql://localhost/app")));
        routing.afterPropertiesSet();
        assertThat(panel(routing).available()).isTrue();

        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy();
        lazy.setTargetDataSource(new DeclaredSource("jdbc:mysql://localhost/app"));
        assertThat(panel(lazy).available()).isTrue();
    }

    @Test
    void unknownUrlRemainsACandidateWhenTheDriverIsPresent() {
        assertThat(panel(mock(DataSource.class)).available()).isTrue();
    }
}
