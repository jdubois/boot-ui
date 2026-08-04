package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerRestClientTraceConfigTest {

    @Test
    void bindsSharedPropertyNamesForcesMetadataOnlyCaptureAndInstallsTraceProvider() {
        SmallRyeConfig config = config(Map.of(
                "bootui.rest-client-trace.capture-headers",
                "true",
                "bootui.rest-client-trace.capture-call-site",
                "false",
                "bootui.rest-client-trace.slow-call-threshold-millis",
                "37",
                "bootui.rest-client-trace.max-entries",
                "11",
                "bootui.rest-client-trace.chatty-call-threshold",
                "4"));
        RestClientTraceRecorder recorder = new BootUiEngineProducer()
                .restClientTraceRecorder(config, new FixedInstance<>((TraceIdProvider) () -> "trace-from-otel"));

        recorder.record(
                "GET",
                "http://localhost/ping",
                "localhost",
                "/ping",
                200,
                38,
                true,
                null,
                "test",
                Map.of("authorization", "Bearer secret"),
                "test-thread");

        assertThat(recorder.isCaptureHeaders()).isFalse();
        assertThat(recorder.isCaptureCallSite()).isFalse();
        assertThat(recorder.getSlowCallThresholdMillis()).isEqualTo(37);
        assertThat(recorder.getMaxEntries()).isEqualTo(11);
        assertThat(recorder.getChattyCallThreshold()).isEqualTo(4);
        assertThat(recorder.recent()).singleElement().satisfies(call -> {
            assertThat(call.traceId()).isEqualTo("trace-from-otel");
            assertThat(call.requestHeaders()).isEmpty();
        });
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    private static final class FixedInstance<T> implements Instance<T> {

        private final T value;

        private FixedInstance(T value) {
            this.value = value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return java.util.List.of(value).iterator();
        }

        @Override
        public T get() {
            return value;
        }
    }
}
