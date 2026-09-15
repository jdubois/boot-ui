package io.github.jdubois.bootui.quarkus.databaseadvisor;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery.Failure;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.lang.annotation.Annotation;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Quarkus/Arc binding of the {@link DatabaseAdvisorDataSourceProvider} SPI: enumerates every
 * {@code javax.sql.DataSource} CDI bean visible to the application (the default datasource plus any additional
 * named ones registered by {@code quarkus-agroal} or a custom producer).
 *
 * <p>Two Quarkus-specific details are handled here rather than in the engine:</p>
 *
 * <ul>
 *   <li><strong>Wrapper de-duplication.</strong> When SQL Trace is active, BootUI itself publishes an
 *       {@code @Alternative} {@code DataSource} that wraps the default Agroal pool. Arc reports both beans, so
 *       identity de-duplication alone would introspect the same physical database twice, under two names, and
 *       double every finding. Each candidate is therefore reduced to the physical pool behind any BootUI
 *       tracing proxy (through the JDBC {@code unwrap} contract the proxy delegates) before de-duplicating.</li>
 *   <li><strong>Meaningful names.</strong> The configured Quarkus datasource name lives on the Agroal
 *       {@code @DataSource("...")} qualifier. This class reads that qualifier <em>reflectively</em>, by
 *       annotation type name, so it never imports {@code io.quarkus.agroal} or {@code io.agroal} and stays
 *       safe to produce unconditionally (see {@code BootUiEngineProducer#databaseAdvisorScanner}) in an
 *       application with no JDBC datasource extension at all. When no qualifier is present the previous
 *       positional naming ({@code default}, {@code datasource-2}, ...) is used as the fallback.</li>
 *   <li><strong>One resolution per bean.</strong> The qualifier pass re-selects beans the unqualified pass can
 *       already have resolved. Each bean is resolved at most once, tracked by its Arc identifier, so a
 *       {@code @Dependent} producer is not invoked twice and a failing producer is reported once instead of
 *       once per pass.</li>
 * </ul>
 *
 * <p>With no datasource extension present {@code Instance<DataSource>} is simply unsatisfied and this provider
 * returns an empty list, so the scanner reports "no DataSource beans were found" instead of failing.</p>
 */
public final class QuarkusDatabaseAdvisorDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private static final String AGROAL_DATA_SOURCE_QUALIFIER = "io.quarkus.agroal.DataSource";
    private static final String TRACED_DATA_SOURCE_MARKER =
            "io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource";

    private final Instance<DataSource> dataSources;
    private final BeanManager beanManager;

    public QuarkusDatabaseAdvisorDataSourceProvider(@Any Instance<DataSource> dataSources) {
        this(dataSources, null);
    }

    public QuarkusDatabaseAdvisorDataSourceProvider(Instance<DataSource> dataSources, BeanManager beanManager) {
        this.dataSources = dataSources;
        this.beanManager = beanManager;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        return discover().requireComplete();
    }

    @Override
    public DatabaseAdvisorDataSourceDiscovery discover() {
        if (dataSources.isUnsatisfied()) {
            return new DatabaseAdvisorDataSourceDiscovery(List.of(), List.of());
        }
        List<Failure> failures = new ArrayList<>();
        List<NamedDataSource> named = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Candidate candidate : candidates(failures)) {
            DataSource physical = physicalDataSource(candidate.dataSource());
            if (physical == null || !seen.add(physical)) {
                continue;
            }
            String name =
                    candidate.qualifiedName() != null ? candidate.qualifiedName() : positionalName(named.size() + 1);
            named.add(new NamedDataSource(name, candidate.dataSource()));
        }
        return new DatabaseAdvisorDataSourceDiscovery(named, failures);
    }

    /** One {@code DataSource} bean plus the datasource name its Agroal qualifier declares, when it has one. */
    private record Candidate(DataSource dataSource, String qualifiedName) {}

    private List<Candidate> candidates(List<Failure> failures) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> resolvedBeans = new java.util.HashSet<>();
        appendCandidates(dataSources, candidates, failures, resolvedBeans);
        if (beanManager != null) {
            // @Any Instance iteration still applies CDI alternative priority: the default SQL Trace
            // alternative can suppress every named pool. Enumerate only qualifier metadata through
            // BeanManager, then resolve each named group through the owning Instance so CDI lifecycle
            // and alternatives within that group remain intact. No connection is borrowed here.
            Set<Annotation> qualifiers = new java.util.LinkedHashSet<>();
            for (Bean<?> bean : beanManager.getBeans(DataSource.class, Any.Literal.INSTANCE)) {
                for (Annotation qualifier : bean.getQualifiers()) {
                    if (AGROAL_DATA_SOURCE_QUALIFIER.equals(
                            qualifier.annotationType().getName())) {
                        qualifiers.add(qualifier);
                    }
                }
            }
            qualifiers.stream()
                    .sorted(java.util.Comparator.comparing(Annotation::toString))
                    .forEach(qualifier ->
                            appendCandidates(dataSources.select(qualifier), candidates, failures, resolvedBeans));
        }
        return candidates;
    }

    /**
     * Appends one CDI selection, skipping any bean an earlier selection already resolved.
     *
     * <p>The qualifier pass deliberately re-selects beans the unqualified pass may already have seen. Resolving
     * such a bean a second time is not free: a {@code @Dependent} producer would build a second pool that
     * identity de-duplication can no longer collapse, and a producer that fails would be reported twice under
     * two different names. Beans are therefore tracked by their Arc identifier — stable per bean, and distinct
     * for two beans that happen to share a positional name.</p>
     */
    private void appendCandidates(
            Instance<DataSource> selection,
            List<Candidate> candidates,
            List<Failure> failures,
            Set<String> resolvedBeans) {
        if (selection instanceof InjectableInstance<DataSource> injectable) {
            for (InstanceHandle<DataSource> handle : injectable.handles()) {
                InjectableBean<DataSource> bean = beanOf(handle);
                String identifier = beanIdentifier(bean);
                if (identifier != null && !resolvedBeans.add(identifier)) {
                    continue;
                }
                // Numbered across every pass, so two distinct beans never share a positional name.
                String name = positionalName(candidates.size() + failures.size() + 1);
                try {
                    String qualifiedName = datasourceName(bean);
                    if (qualifiedName != null) {
                        name = qualifiedName;
                    }
                    DataSource dataSource = handle.get();
                    if (dataSource == null) {
                        failures.add(new Failure(name, "Datasource bean resolved to null."));
                    } else {
                        candidates.add(new Candidate(dataSource, name));
                    }
                } catch (RuntimeException | LinkageError ex) {
                    failures.add(new Failure(name, "Datasource bean could not be resolved: " + ex.getMessage()));
                }
            }
            return;
        }
        for (DataSource dataSource : selection) {
            if (dataSource != null) {
                candidates.add(new Candidate(dataSource, null));
            }
        }
    }

    /** The handle's bean metadata, or {@code null} when the container will not describe it. */
    private static InjectableBean<DataSource> beanOf(InstanceHandle<DataSource> handle) {
        try {
            return handle.getBean();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** Arc's stable per-bean identifier, or {@code null} when there is none to de-duplicate on. */
    private static String beanIdentifier(InjectableBean<DataSource> bean) {
        if (bean == null) {
            return null;
        }
        try {
            String identifier = bean.getIdentifier();
            return identifier == null || identifier.isBlank() ? null : identifier;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * The value of the bean's {@code @io.quarkus.agroal.DataSource("name")} qualifier, read by annotation type
     * name so this class never links the Agroal extension's types.
     */
    private static String datasourceName(InjectableBean<DataSource> bean) {
        if (bean == null) {
            return null;
        }
        for (Annotation qualifier : bean.getQualifiers()) {
            if (!AGROAL_DATA_SOURCE_QUALIFIER.equals(qualifier.annotationType().getName())) {
                continue;
            }
            try {
                Object value = qualifier.annotationType().getMethod("value").invoke(qualifier);
                if (value instanceof String name && !name.isBlank()) {
                    return name;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * The physical pool behind a BootUI SQL Trace proxy, so the wrapper and the wrapped pool de-duplicate to
     * one datasource. Anything else is its own physical datasource.
     */
    private static DataSource physicalDataSource(DataSource dataSource) {
        if (!isTracingProxy(dataSource)) {
            return dataSource;
        }
        try {
            DataSource unwrapped = dataSource.unwrap(DataSource.class);
            return unwrapped == null ? dataSource : unwrapped;
        } catch (SQLException | RuntimeException ex) {
            return dataSource;
        }
    }

    private static boolean isTracingProxy(DataSource dataSource) {
        for (Class<?> interfaceType : dataSource.getClass().getInterfaces()) {
            if (TRACED_DATA_SOURCE_MARKER.equals(interfaceType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String positionalName(int position) {
        return position == 1 ? "default" : "datasource-" + position;
    }
}
