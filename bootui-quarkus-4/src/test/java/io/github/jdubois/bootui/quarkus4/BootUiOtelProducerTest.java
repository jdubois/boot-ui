package io.github.jdubois.bootui.quarkus4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiOtelProducerTest {

    @Test
    void processorsDiscoveredBeforeFreezeCaptureAfterAdmissionWithoutEarlyStoreResolution() {
        Instance<TelemetryStore> stores = instance(mock(TelemetryStore.class));
        Instance<SelfTelemetryClassifier> classifiers =
                instance(new SelfTelemetryClassifier(true, "/bootui", "/bootui/api"));
        QuarkusTelemetrySettings settings = new QuarkusTelemetrySettings(mock(Config.class));
        BootUiOtelProducer producer = new BootUiOtelProducer();

        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            SpanProcessor capture = producer.bootUiSpanProcessor(stores, settings, classifiers);
            SpanProcessor identity = producer.bootUiIdentitySpanProcessor(settings, mock(Config.class), stores);
            assertThat(capture.isEndRequired()).isTrue();
            assertThat(identity.isStartRequired()).isTrue();
            assertThat(settings.enabled()).isFalse();
            assertThat(settings.enrichmentEnabled()).isFalse();
            capture.forceFlush();
            identity.forceFlush();

            try (SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(identity)
                    .addSpanProcessor(capture)
                    .build()) {
                provider.get("test").spanBuilder("before-freeze").startSpan().end();
                verifyNoInteractions(stores, classifiers);

                activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
                provider.get("test").spanBuilder("after-freeze").startSpan().end();

                verify(stores, times(2)).get();
                verify(classifiers).get();
                verify(stores.get()).spanStarted(anyString(), anyString());
                verify(stores.get()).add(any(), anyBoolean());
            }
        }
    }

    @Test
    void shutdownDoesNotInitializeDormantProcessorsOrAllowLateCapture() {
        Instance<TelemetryStore> stores = instance(mock(TelemetryStore.class));
        Instance<SelfTelemetryClassifier> classifiers = instance(mock(SelfTelemetryClassifier.class));
        QuarkusTelemetrySettings settings = new QuarkusTelemetrySettings(mock(Config.class));
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            SpanProcessor capture = new BootUiOtelProducer().bootUiSpanProcessor(stores, settings, classifiers);
            assertThat(capture.shutdown().isSuccess()).isTrue();
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            capture.onEnd(mock(io.opentelemetry.sdk.trace.ReadableSpan.class));
            capture.forceFlush();
            verifyNoInteractions(stores, classifiers);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }
}
