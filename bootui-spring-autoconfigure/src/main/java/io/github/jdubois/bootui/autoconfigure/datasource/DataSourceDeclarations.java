package io.github.jdubois.bootui.autoconfigure.datasource;

import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;

/**
 * Reads only existing datasource objects; sidebar discovery must not initialize a lazy pool.
 *
 * <p>Every source this returns is safe for a caller to interrogate — read its JDBC URL, test its type — with
 * no risk of creating a bean or opening a connection. A declaration that cannot be reduced to such an object,
 * because the bean has not been created or because it is a dynamic Spring AOP proxy whose every method
 * initializes its target, is reported through {@link Snapshot#incomplete()} rather than emitted. Callers must
 * read {@code incomplete()} as "something is declared here that could not be observed", never as "nothing".</p>
 */
public final class DataSourceDeclarations {

    private DataSourceDeclarations() {}

    public record Snapshot(boolean present, boolean incomplete, List<DataSource> sources) {
        public Snapshot {
            sources = List.copyOf(sources);
        }
    }

    public static Snapshot inspect(ApplicationContext context) {
        String[] names = context.getBeanNamesForType(DataSource.class, true, false);
        if (names.length == 0) {
            return new Snapshot(false, false, List.of());
        }
        if (!(context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory)) {
            return new Snapshot(true, true, List.of());
        }
        ArrayDeque<DataSource> pending = new ArrayDeque<>();
        boolean incomplete = false;
        for (String name : names) {
            Object singleton = factory.getSingleton(name);
            if (singleton instanceof DataSource dataSource) {
                pending.add(dataSource);
            } else {
                incomplete = true;
            }
        }
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DataSource> sources = new ArrayList<>();
        while (!pending.isEmpty() && seen.size() < 64) {
            DataSource source = pending.removeFirst();
            if (!seen.add(source)) {
                continue;
            }
            if (source instanceof SqlTracedDataSource) {
                try {
                    DataSource target = source.unwrap(DataSource.class);
                    if (target != null && target != source) {
                        pending.add(target);
                        continue;
                    }
                } catch (SQLException | RuntimeException ex) {
                    incomplete = true;
                }
            }
            if (SpringAopDataSources.isProxy(source)) {
                DataSource target = SpringAopDataSources.staticTarget(source);
                if (target != null) {
                    // The target already exists: hand callers the pool instead of a proxy that would route
                    // every later question through an interceptor chain.
                    pending.add(target);
                    continue;
                }
                // A dynamic target source initializes its target on any invocation, including the URL getters
                // the database detectors call. Emitting this proxy would turn rendering the sidebar into bean
                // creation, so it is reported as unobserved instead.
                incomplete = true;
                continue;
            }
            if (DelegatingDataSources.isRouting(source.getClass())) {
                var targets = DelegatingDataSources.routingTargets(source);
                if (!targets.isEmpty()) {
                    pending.addAll(targets.values());
                    continue;
                }
                incomplete = true;
            } else if (DelegatingDataSources.isSingleTarget(source.getClass())) {
                DataSource target = DelegatingDataSources.target(source);
                if (target != null) {
                    pending.add(target);
                    continue;
                }
                incomplete = true;
            }
            sources.add(source);
        }
        return new Snapshot(true, incomplete || !pending.isEmpty(), sources);
    }
}
