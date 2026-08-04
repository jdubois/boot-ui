package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

class ReactiveRestClientTraceControllerTests {

    private RestClientTraceRecorder recorder(boolean enabled) {
        return new RestClientTraceRecorder(enabled, true, false, false, 10, 1000, 2000, 200, 5);
    }

    private RestClientTraceRecorder instrumentedRecorder(boolean enabled) {
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(enabled, true, false, false, 10, 1000, 2000, 200, 5);
        recorder.registerClientCustomization("WebClient");
        return recorder;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RestClientTraceRecorder> recorderProvider(RestClientTraceRecorder recorder) {
        ObjectProvider<RestClientTraceRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    private BootUiExposure exposure() {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.MASKED);
        return new BootUiExposure(properties);
    }

    private ReactiveRestClientTraceController controller(RestClientTraceRecorder recorder) {
        return new ReactiveRestClientTraceController(recorderProvider(recorder), exposure());
    }

    @Test
    void reportsUnavailableWhenNoRecorderConfigured() {
        ReactiveRestClientTraceController controller =
                new ReactiveRestClientTraceController(recorderProvider(null), exposure());

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("REST client tracing is not configured");
    }

    @Test
    void reportsDisabledReasonWhenTracingOff() {
        ReactiveRestClientTraceController controller = controller(recorder(false));

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("disabled");
    }

    @Test
    void reportsNotInstrumentedYetWhenNoClientHasBeenCustomized() {
        ReactiveRestClientTraceController controller = controller(recorder(true));

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("No RestClient, RestTemplate, or WebClient");
    }

    @Test
    void reportsCapturedCalls() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true);
        recorder.record(
                "GET",
                "https://api.example.com/orders/42",
                "api.example.com",
                "/orders/42",
                200,
                55L,
                true,
                null,
                "WebClient",
                Map.of(),
                "main");
        ReactiveRestClientTraceController controller = controller(recorder);

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isTrue();
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).method()).isEqualTo("GET");
        assertThat(report.entries().get(0).clientType()).isEqualTo("WebClient");
    }

    @Test
    void clearEmptiesTheBuffer() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true);
        recorder.record(
                "POST",
                "https://api.example.com/orders",
                "api.example.com",
                "/orders",
                201,
                20L,
                true,
                null,
                "WebClient",
                Map.of(),
                "main");
        ReactiveRestClientTraceController controller = controller(recorder);

        RestClientTraceReport report = controller.clear();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void recordingTogglePausesAndResumes() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true);
        ReactiveRestClientTraceController controller = controller(recorder);

        RestClientTraceReport paused = controller.recording(new RestClientTraceRecordingRequest(false));
        assertThat(paused.capturing()).isFalse();
        assertThat(paused.warnings()).anyMatch(w -> w.contains("paused"));

        RestClientTraceReport toggled = controller.recording(null);
        assertThat(toggled.capturing()).isTrue();
    }

    @Test
    void streamPublishesRecorderChanges() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true);
        ReactiveRestClientTraceController controller = controller(recorder);

        StepVerifier.create(controller.stream())
                .then(recorder::clear)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("update");
                    assertThat(event.data()).containsKey("ts");
                })
                .thenCancel()
                .verify(java.time.Duration.ofSeconds(2));
    }
}
