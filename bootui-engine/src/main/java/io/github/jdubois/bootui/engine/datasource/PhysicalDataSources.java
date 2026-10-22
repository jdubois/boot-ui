package io.github.jdubois.bootui.engine.datasource;

import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.sql.SQLException;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/**
 * Reduces a {@link DataSource} to the physical object behind BootUI's SQL Trace proxy and any framework wrappers,
 * so two names for one pool compare as the same datasource by identity.
 *
 * <p>The engine owns only the step it can take without a framework: the SQL Trace proxy delegates the JDBC
 * {@code unwrap} contract to its target, and it is BootUI's own code. Everything else is a framework wrapper
 * (Spring's delegating wrappers, static AOP proxies) that an adapter passes in as {@code frameworkStep}. Neither
 * step may open a connection, and neither should create a bean or resolve a dynamic proxy target.</p>
 */
public final class PhysicalDataSources {

    /** Bound on wrapper nesting, so a mis-configured cyclic wrapper chain cannot spin here. */
    static final int MAX_WRAPPER_DEPTH = 16;

    private PhysicalDataSources() {}

    /** The physical datasource behind BootUI's SQL Trace proxy only. */
    public static DataSource physical(DataSource dataSource) {
        return physical(dataSource, current -> null);
    }

    /**
     * The physical datasource behind BootUI's SQL Trace proxy and the wrappers {@code frameworkStep} can look
     * through. {@code frameworkStep} returns the single datasource a wrapper forwards to, or {@code null} when its
     * argument is not a wrapper it understands or refuses to describe its target. A routing wrapper has no single
     * physical target, so it is its own identity.
     */
    public static DataSource physical(DataSource dataSource, UnaryOperator<DataSource> frameworkStep) {
        if (dataSource == null) {
            return null;
        }
        DataSource current = dataSource;
        for (int depth = 0; depth < MAX_WRAPPER_DEPTH; depth++) {
            DataSource next = unwrapOnce(current, frameworkStep);
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * By interface name as well as by type: a proxy kept across a DevTools restart implements the marker of an
     * earlier class loader, which {@code instanceof} would not recognise.
     */
    private static boolean isTracingProxy(DataSource dataSource) {
        if (dataSource instanceof SqlTracedDataSource) {
            return true;
        }
        for (Class<?> type = dataSource.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> contract : type.getInterfaces()) {
                if (SqlTracedDataSource.class.getName().equals(contract.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static DataSource unwrapOnce(DataSource dataSource, UnaryOperator<DataSource> frameworkStep) {
        if (isTracingProxy(dataSource)) {
            try {
                return dataSource.unwrap(DataSource.class);
            } catch (SQLException | RuntimeException ex) {
                return null;
            }
        }
        try {
            return frameworkStep.apply(dataSource);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
