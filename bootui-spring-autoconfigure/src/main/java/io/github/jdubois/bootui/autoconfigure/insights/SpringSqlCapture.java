package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Whether this Spring application's SQL is recorded, for Runtime Insights ({@code docs/PLAN-v2.md} §5.5): BootUI records
 * the statements of each {@code DataSource} bean its post-processor traced, so without a traced one, as in an R2DBC
 * application, the checks that read SQL are unavailable rather than evaluated over nothing. Shared by Spring MVC and
 * WebFlux. R2DBC is looked up by name, so an application without it never loads its types.
 */
final class SpringSqlCapture implements Supplier<SqlCapture> {

    static final String CONNECTION_FACTORY = "io.r2dbc.spi.ConnectionFactory";

    private final ApplicationContext context;
    private final ObjectProvider<SqlTraceRecorder> recorder;
    private final String connectionFactory;
    private volatile Boolean r2dbc;

    SpringSqlCapture(ApplicationContext context) {
        this(context, CONNECTION_FACTORY);
    }

    /** With the connection factory type named {@code connectionFactory}, for tests without R2DBC. */
    SpringSqlCapture(ApplicationContext context, String connectionFactory) {
        this.context = context;
        this.recorder = context.getBeanProvider(SqlTraceRecorder.class);
        this.connectionFactory = connectionFactory;
    }

    @Override
    public SqlCapture get() {
        return SqlCapture.of(recorder.getIfAvailable(), true, r2dbc());
    }

    /** Whether the application has an R2DBC connection factory bean, read once its context is refreshed. */
    private boolean r2dbc() {
        Boolean known = r2dbc;
        if (known != null) {
            return known;
        }
        ClassLoader loader = context.getClassLoader();
        boolean found = false;
        if (ClassUtils.isPresent(connectionFactory, loader)) {
            try {
                found = context.getBeanNamesForType(ClassUtils.forName(connectionFactory, loader), false, false).length
                        > 0;
            } catch (ClassNotFoundException | LinkageError ex) {
                found = false;
            }
        }
        r2dbc = found;
        return found;
    }
}
