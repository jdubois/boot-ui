package io.github.jdubois.bootui.quarkus.sqltrace;

import io.agroal.api.AgroalDataSource;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.quarkus.agroal.runtime.AgroalDataSourceUtil;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;

/**
 * SQL Trace capture wiring for the Quarkus adapter: produces the shared engine {@link SqlTraceRecorder}
 * (capped store + grouping/stats/N+1 assembly, byte-identical to Spring) and an {@link Alternative}
 * {@code DataSource} that wraps the application's default Agroal pool with the framework-neutral
 * {@link SqlTracingProxies} so every JDBC statement is recorded. The proxy advertises
 * {@code AgroalDataSource} in addition to {@code DataSource}, so concrete-type injectors still resolve a
 * traced pool, and {@code unwrap} is delegated so connection-pool discovery / metrics still reach the real
 * pool.
 *
 * <p><strong>Deliberately not annotated with a CDI scope; the deployment processor excludes it from bean
 * discovery unless the {@code AGROAL} capability is present (dev/test only).</strong> Mirroring
 * {@code BootUiAgroalProducer}, it references {@code io.agroal} types that must stay absent in an app
 * without a JDBC datasource extension. This wrap captures <em>manual</em> {@code DataSource}/JDBC access;
 * Hibernate ORM resolves its pool through Agroal's own registry rather than this CDI bean, so ORM-issued SQL
 * is captured separately by {@code BootUiHibernateStatementInspector} (gated on the Hibernate capability).
 * Recording defaults follow {@code bootui.sql-trace.*}; the panel stays dark in production because the
 * console is never wired there.</p>
 */
public class BootUiSqlTraceProducer {

    @Produces
    @Singleton
    public SqlTraceRecorder sqlTraceRecorder(Config config) {
        boolean enabled = config.getOptionalValue("bootui.sql-trace.enabled", Boolean.class)
                .orElse(true);
        boolean recording = config.getOptionalValue("bootui.sql-trace.recording", Boolean.class)
                .orElse(true);
        boolean captureParameters = config.getOptionalValue("bootui.sql-trace.capture-parameters", Boolean.class)
                .orElse(false);
        int maxEntries = config.getOptionalValue("bootui.sql-trace.max-entries", Integer.class)
                .orElse(200);
        long slowThreshold = config.getOptionalValue("bootui.sql-trace.slow-query-threshold-millis", Long.class)
                .orElse(100L);
        int maxSqlLength = config.getOptionalValue("bootui.sql-trace.max-sql-length", Integer.class)
                .orElse(2000);
        int maxParamLength = config.getOptionalValue("bootui.sql-trace.max-parameter-length", Integer.class)
                .orElse(200);
        int nPlusOne = config.getOptionalValue("bootui.sql-trace.n-plus-one-threshold", Integer.class)
                .orElse(5);
        return new SqlTraceRecorder(
                enabled,
                recording,
                captureParameters,
                maxEntries,
                slowThreshold,
                maxSqlLength,
                maxParamLength,
                nPlusOne);
    }

    @Produces
    @Singleton
    @Alternative
    @Priority(1)
    public DataSource tracedDataSource(SqlTraceRecorder recorder) {
        Optional<AgroalDataSource> defaultPool =
                AgroalDataSourceUtil.dataSourceIfActive(DataSourceUtil.DEFAULT_DATASOURCE_NAME);
        AgroalDataSource real = defaultPool.orElseThrow();
        if (recorder.isEnabled()) {
            recorder.registerDataSource(DataSourceUtil.DEFAULT_DATASOURCE_NAME);
            return SqlTracingProxies.wrap(real, recorder, AgroalDataSource.class);
        }
        return real;
    }
}
