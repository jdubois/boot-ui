package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Verifies that every JDK proxy {@link SqlTracingProxies} creates at runtime is registered as
 * native-image proxy metadata, so SQL tracing works in a GraalVM native image instead of failing.
 */
class SqlTraceRuntimeHintsTests {

    private final RuntimeHints hints = new RuntimeHints();

    SqlTraceRuntimeHintsTests() {
        new SqlTraceRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void registersDataSourceProxyWithMarkerAndAutoCloseable() {
        assertThat(RuntimeHintsPredicates.proxies()
                        .forInterfaces(DataSource.class, AutoCloseable.class, SqlTracedDataSource.class))
                .accepts(hints);
    }

    @Test
    void registersConnectionAndStatementProxies() {
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(Connection.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(Statement.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(PreparedStatement.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(CallableStatement.class))
                .accepts(hints);
    }

    @Test
    void registrationsMatchTheInterfaceSetsUsedAtRuntime() {
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SqlTracingProxies.dataSourceInterfaces()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SqlTracingProxies.connectionInterfaces()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SqlTracingProxies.statementInterfaces()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SqlTracingProxies.preparedStatementInterfaces()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SqlTracingProxies.callableStatementInterfaces()))
                .accepts(hints);
    }
}
