package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.github.jdubois.bootui.quarkus.databaseadvisor.QuarkusDatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Shared, Docker-free Arc proof for {@code QuarkusDatabaseAdvisorDataSourceProvider}'s named-pool qualifier
 * pass, run twice: once with SQL Trace on and once with it off (see the two concrete subclasses).
 *
 * <p>The provider walks CDI twice — an unqualified {@code @Any} pass, then one pass per Agroal
 * {@code @DataSource("...")} qualifier — because {@code @Any} iteration still applies alternative priority, so
 * BootUI's own prioritized default SQL Trace alternative would otherwise suppress every named pool. The
 * {@code mysql-live} lane already proves a real named Agroal pool surfaces with tracing on, but it needs
 * Docker and only covers that one mode. These tests pin the part that has no server dependency at all: with a
 * default <em>and</em> a named H2 datasource present, both passes together must publish exactly two stable
 * identities, in both tracing modes, without resolving any bean twice and without borrowing a connection.</p>
 *
 * <p>Discovery is what the Database Advisor, PostgreSQL and MySQL services call before an explicit read, so a
 * duplicated resolution is not cosmetic: it would introspect the same database twice under two names, double
 * every finding, report a failing producer once per pass, and — for a {@code @Dependent} producer — build a
 * second pool that identity de-duplication can no longer collapse.</p>
 */
abstract class NamedDataSourceDiscoveryAssertions {

    /**
     * A second, named H2 datasource alongside the module's default one. Declared through a test profile rather
     * than {@code application.properties} so the sibling Connection Pools test keeps its single-pool fixture.
     */
    static Map<String, String> namedH2Datasource() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("quarkus.datasource.reporting.db-kind", "h2");
        overrides.put("quarkus.datasource.reporting.username", "bootui_app");
        overrides.put("quarkus.datasource.reporting.jdbc.url", "jdbc:h2:mem:bootui-reporting;DB_CLOSE_DELAY=-1");
        overrides.put("quarkus.datasource.reporting.jdbc.metrics.enabled", "true");
        return overrides;
    }

    @Inject
    @Any
    Instance<DataSource> dataSources;

    @Inject
    BeanManager beanManager;

    @Inject
    AgroalDataSource defaultPool;

    @Inject
    @io.quarkus.agroal.DataSource("reporting")
    AgroalDataSource reportingPool;

    /** How the default datasource must appear in this tracing mode: wrapped by the proxy, or the pool itself. */
    abstract void assertDefaultEntry(DataSource discovered, AgroalDataSource pool);

    @Test
    void publishesTheDefaultAndNamedPoolExactlyOnceWithoutResolvingABeanTwice() {
        long defaultAcquisitions = defaultPool.getMetrics().acquireCount();
        long reportingAcquisitions = reportingPool.getMetrics().acquireCount();
        QuarkusDatabaseAdvisorDataSourceProvider provider =
                new QuarkusDatabaseAdvisorDataSourceProvider(dataSources, beanManager);

        DatabaseAdvisorDataSourceDiscovery first = provider.discover();

        assertThat(first.failures())
                .as("a resolution failure would be reported once per pass, so none is the only honest result")
                .isEmpty();
        assertThat(first.dataSources())
                .extracting(NamedDataSource::name)
                .as("the qualifier pass must add the named pool, not re-add the default one")
                .containsExactlyInAnyOrder("default", "reporting");

        Map<String, DataSource> discovered = byName(first);
        assertDefaultEntry(discovered.get("default"), defaultPool);
        assertThat(discovered.get("reporting"))
                .as("the named entry is the named pool itself, which the tracing alternative never wraps")
                .isSameAs(reportingPool);
        assertThat(discovered.get("default"))
                .as("two distinct physical pools, not the same one under two names")
                .isNotSameAs(discovered.get("reporting"));

        DatabaseAdvisorDataSourceDiscovery second = provider.discover();

        assertThat(second.failures()).isEmpty();
        assertThat(byName(second))
                .as("identities are stable: repeated discovery resolves the same beans, never new instances")
                .containsExactlyInAnyOrderEntriesOf(discovered);
        assertThat(defaultPool.getMetrics().acquireCount())
                .as("discovery reads declarations only and must never borrow a connection")
                .isEqualTo(defaultAcquisitions);
        assertThat(reportingPool.getMetrics().acquireCount())
                .as("discovery reads declarations only and must never borrow a connection")
                .isEqualTo(reportingAcquisitions);
    }

    private static Map<String, DataSource> byName(DatabaseAdvisorDataSourceDiscovery discovery) {
        Map<String, DataSource> byName = new LinkedHashMap<>();
        for (NamedDataSource source : discovery.dataSources()) {
            assertThat(byName.put(source.name(), source.dataSource()))
                    .as("no datasource name is published twice")
                    .isNull();
        }
        return byName;
    }
}
