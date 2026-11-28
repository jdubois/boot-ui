package io.github.jdubois.bootui.quarkus4.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.agroal.api.AgroalDataSource;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/** Pins the shared {@code bootui.sql-trace.*} retention keys and defaults on the Quarkus recorder producer. */
class BootUiSqlTraceProducerConfigTest {

    @Test
    void nativeDenialReturnsHostConnectionsAndFirstAdmissionLazilyStartsTracing() throws Exception {
        AgroalDataSource host = mock(AgroalDataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(host.getConnection()).thenReturn(connection);
        when(host.getConnection("user", "password")).thenReturn(connection);
        when(host.unwrap(DataSource.class)).thenReturn(host);
        when(host.isWrapperFor(DataSource.class)).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("select 1")).thenReturn(true);
        SqlTraceRecorder recorder = recorder(Map.of("bootui.sql-trace.capture-call-site", "false"));
        AtomicInteger resolutions = new AtomicInteger();

        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            DataSource source = BootUiSqlTraceProducer.deferredDataSource(host, () -> {
                resolutions.incrementAndGet();
                return recorder;
            });
            assertThat(source).isInstanceOf(AgroalDataSource.class);
            assertThat(source.getClass().getInterfaces())
                    .containsExactly(SqlTracingProxies.dataSourceInterfaces(AgroalDataSource.class));
            assertThat(source.unwrap(DataSource.class)).isSameAs(host);
            assertThat(source.isWrapperFor(DataSource.class)).isTrue();
            assertThat(source.getConnection("user", "password")).isSameAs(connection);
            assertThat(source.getConnection()).isSameAs(connection);
            source.getConnection().createStatement().execute("select 1");
            assertThat(recorder.recent()).isEmpty();
            assertThat(resolutions).hasValue(0);

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            Connection traced = source.getConnection();
            assertThat(traced).isNotSameAs(connection);
            traced.createStatement().execute("select 1");
            assertThat(recorder.recent()).hasSize(1);
            assertThat(resolutions).hasValue(1);
        }
    }

    @Test
    void nativeDenialPreservesTheHostsJdbcFailureWithoutResolvingCapture() throws Exception {
        AgroalDataSource host = mock(AgroalDataSource.class);
        SQLException failure = new SQLException("host connection failure");
        when(host.getConnection()).thenThrow(failure);
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            DataSource source = BootUiSqlTraceProducer.deferredDataSource(host, () -> {
                throw new AssertionError("Denied capture must not resolve a recorder");
            });
            assertThatThrownBy(source::getConnection).isSameAs(failure);
        }
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> unresolvable() {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static SqlTraceRecorder recorder(Map<String, String> properties) {
        Instance<TraceIdSource> traceIdProvider = unresolvable();
        Instance<SpanEnricher> spanEnricher = unresolvable();
        return new BootUiSqlTraceProducer()
                .sqlTraceRecorder(config(properties), traceIdProvider, spanEnricher, new RequestPhases());
    }

    @Test
    void reservesAQuarterOfTheBufferByDefault() {
        SqlTraceRecorder recorder = recorder(Map.of());

        assertThat(recorder.getMaxEntries()).isEqualTo(200);
        assertThat(recorder.getReservedCapacity()).isEqualTo(50);
        assertThat(recorder.retention().slowThresholdMillis()).isEqualTo(100L);
    }

    @Test
    void bindsTheReservedShareOverride() {
        SqlTraceRecorder recorder = recorder(Map.of(
                "bootui.sql-trace.max-entries", "40",
                "bootui.sql-trace.reserved-share-percent", "10",
                "bootui.sql-trace.slow-query-threshold-millis", "0"));

        assertThat(recorder.getReservedCapacity()).isEqualTo(4);
        assertThat(recorder.retention().slowThresholdMillis()).isZero();
    }
}
