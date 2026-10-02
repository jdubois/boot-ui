package io.github.jdubois.bootui.quarkus.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/** Pins the shared {@code bootui.sql-trace.*} retention keys and defaults on the Quarkus recorder producer. */
class BootUiSqlTraceProducerConfigTest {

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
