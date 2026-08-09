package io.github.jdubois.bootui.quarkus.databaseadvisor;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Quarkus/Arc binding of the {@link DatabaseAdvisorDataSourceProvider} SPI: enumerates every
 * {@code javax.sql.DataSource} CDI bean visible to the application (the default datasource plus any
 * additional named ones registered by {@code quarkus-agroal}, {@code quarkus-reactive-*}, or a custom
 * producer), de-duplicated by identity.
 *
 * <p>Unlike {@code QuarkusAgroalConnectionPoolProvider}, this class never imports {@code io.agroal.*} or the
 * {@code io.quarkus.agroal.DataSource} qualifier annotation, so it is safe to produce <em>unconditionally</em>
 * (see {@code BootUiEngineProducer#databaseAdvisorScanner}) even in an application without any JDBC datasource
 * extension — {@code Instance<DataSource>} is then simply unsatisfied and this provider returns an empty
 * list, so the scanner reports "no DataSource beans were found" instead of failing. Because the per-bean
 * datasource name is only discoverable through the (optional) Agroal-specific qualifier, beans are named
 * positionally ({@code "default"} for the first, {@code "datasource-2"}, {@code "datasource-3"}, ... for any
 * additional ones) — a documented, reduced-fidelity naming compared to the Spring adapter, which resolves the
 * Spring bean name.</p>
 */
public final class QuarkusDatabaseAdvisorDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private final Instance<DataSource> dataSources;

    public QuarkusDatabaseAdvisorDataSourceProvider(@Any Instance<DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        if (dataSources.isUnsatisfied()) {
            return List.of();
        }
        List<NamedDataSource> named = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int index = 1;
        for (DataSource dataSource : dataSources) {
            if (dataSource == null || !seen.add(dataSource)) {
                continue;
            }
            String name = index == 1 ? "default" : "datasource-" + index;
            named.add(new NamedDataSource(name, dataSource));
            index++;
        }
        return named;
    }
}
