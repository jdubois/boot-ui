package io.github.jdubois.bootui.quarkus.sqltrace;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Captures Hibernate ORM SQL into the shared engine {@link SqlTraceRecorder} on the Quarkus adapter.
 *
 * <p>The Quarkus adapter's JDBC capture ({@code BootUiSqlTraceProducer}) wraps the CDI {@code DataSource}
 * bean, which only sees <em>manual</em> JDBC access — Hibernate ORM resolves its pool through Agroal's own
 * registry rather than that CDI {@code @Alternative}, so ORM-issued SQL bypasses the wrap. This
 * {@link StatementInspector} closes that gap: Quarkus wires it into the persistence unit (via
 * {@link PersistenceUnitExtension}), so Hibernate calls {@link #inspect(String)} as it prepares each
 * statement and the SQL is recorded into the same buffer the panel and SSE stream already serve. Between the
 * two mechanisms the Quarkus SQL Trace panel reaches parity with Spring (whose Hibernate uses the wrapped
 * {@code DataSource} bean), regardless of whether SQL originates from the ORM or from raw JDBC.</p>
 *
 * <p><strong>Deliberately not gated by a CDI scope alone: the deployment processor excludes it from bean
 * discovery unless the {@code HIBERNATE_ORM} capability is present (dev/test only).</strong> It statically
 * references {@code org.hibernate.resource.jdbc.spi.StatementInspector} and
 * {@code io.quarkus.hibernate.orm.PersistenceUnitExtension}, optional types that must stay absent in an app
 * without {@code quarkus-hibernate-orm} (R2) — so {@code BootUiQuarkusProcessor#registerHibernateSqlTrace}
 * pins it unremovable when Hibernate is present and excludes it otherwise via
 * {@code io.quarkus.arc.deployment.ExcludedTypeBuildItem}, mirroring {@code BootUiHibernateProducer}.</p>
 *
 * <p>The recorder is resolved through an {@link Instance} so capture degrades to a no-op (returning the SQL
 * unchanged) if no recorder is present — Hibernate always implies an Agroal datasource, so in practice it is
 * resolvable. Honest fidelity caveat: the {@code StatementInspector} SPI exposes only the SQL text at prepare
 * time with no execution-end hook, so per-statement duration, affected-row counts and bound parameters are
 * not available for ORM SQL (duration is recorded as {@code 0}); statement text, type, category, execution
 * count and N+1 detection are full-fidelity. Bound parameters are never captured here, so the panel cannot
 * leak ORM parameter values regardless of {@code bootui.sql-trace.capture-parameters}. The unqualified
 * {@link PersistenceUnitExtension} binds only the <em>default</em> persistence unit, so SQL issued by a named
 * persistence unit/datasource is not captured here (the common single-datasource case, including the sample
 * app, is the default PU).</p>
 */
@Singleton
@PersistenceUnitExtension
public class BootUiHibernateStatementInspector implements StatementInspector {

    private static final long serialVersionUID = 1L;

    /**
     * The datasource name surfaced on the panel for ORM-sourced capture. Hibernate prepares against the
     * application's default datasource; registering it makes the panel report "available" (a wrapped/feeding
     * source exists) even when nothing injects the CDI {@code DataSource} that {@code BootUiSqlTraceProducer}
     * wraps — which is the common case in an ORM-only app such as the sample app.
     */
    private static final String DATA_SOURCE_NAME = "<default>";

    private final transient Instance<SqlTraceRecorder> recorder;
    private final transient AtomicBoolean registered = new AtomicBoolean(false);

    @Inject
    public BootUiHibernateStatementInspector(Instance<SqlTraceRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public String inspect(String sql) {
        // A StatementInspector is Serializable (the Hibernate SPI contract) and our collaborators are
        // transient; guard against a deserialized instance with null fields rather than NPE on every
        // subsequent ORM query. Not reachable under the dev/test-only @PersistenceUnitExtension wiring
        // (Quarkus does not serialize the SessionFactory at runtime), but cheap and strictly correct.
        if (recorder == null || registered == null) {
            return sql;
        }
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec != null && rec.isEnabled() && sql != null) {
            if (registered.compareAndSet(false, true)) {
                rec.registerDataSource(DATA_SOURCE_NAME);
            }
            rec.record(
                    StatementType.PREPARED,
                    SqlTracingProxies.categoryOf(sql),
                    sql,
                    List.of(),
                    0L,
                    true,
                    null,
                    null,
                    0,
                    null,
                    Thread.currentThread().getName());
        }
        return sql;
    }
}
