package io.github.jdubois.bootui.autoconfigure.restclienttrace;

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

class RestClientTraceControllerTests {

    private RestClientTraceRecorder recorder(boolean enabled) {
        return new RestClientTraceRecorder(enabled, true, false, false, 10, 1000, 2000, 200, 5);
    }

    private RestClientTraceRecorder instrumentedRecorder(boolean enabled, boolean captureHeaders) {
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(enabled, true, captureHeaders, false, 10, 1000, 2000, 200, 5);
        recorder.registerClientCustomization("RestTemplate");
        return recorder;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RestClientTraceRecorder> recorderProvider(RestClientTraceRecorder recorder) {
        ObjectProvider<RestClientTraceRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    private BootUiExposure exposure(ValueExposure valueExposure) {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(valueExposure);
        return new BootUiExposure(properties);
    }

    private RestClientTraceController controller(RestClientTraceRecorder recorder, ValueExposure exposure) {
        return new RestClientTraceController(recorderProvider(recorder), exposure(exposure));
    }

    @Test
    void reportsUnavailableWhenNoRecorderConfigured() {
        RestClientTraceController controller = controller(null, ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("REST client tracing is not configured");
    }

    @Test
    void reportsDisabledReasonWhenTracingOff() {
        RestClientTraceController controller = controller(recorder(false), ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("disabled");
    }

    @Test
    void reportsNotInstrumentedYetWhenNoClientHasBeenCustomized() {
        RestClientTraceController controller = controller(recorder(true), ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("No RestClient, RestTemplate, or WebClient");
    }

    @Test
    void reportsCapturedCalls() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, false);
        recorder.record(
                "GET",
                "https://api.example.com/orders/42",
                "api.example.com",
                "/orders/42",
                200,
                150,
                true,
                null,
                "RestTemplate",
                Map.of(),
                "main");
        RestClientTraceController controller = controller(recorder, ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.available()).isTrue();
        assertThat(report.capturing()).isTrue();
        assertThat(report.clientTypes()).containsExactly("RestTemplate");
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).method()).isEqualTo("GET");
        assertThat(report.entries().get(0).status()).isEqualTo(200);
        assertThat(report.entries().get(0).clientType()).isEqualTo("RestTemplate");
        assertThat(report.topCalls()).hasSize(1);
        assertThat(report.stats().totalCalls()).isEqualTo(1);
    }

    @Test
    void reportsSlowCallsAndFailuresInStats() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, false);
        recorder.record(
                "GET",
                "https://api.example.com/slow",
                "api.example.com",
                "/slow",
                200,
                5000,
                true,
                null,
                "RestTemplate",
                Map.of(),
                "main");
        recorder.record(
                "GET",
                "https://api.example.com/down",
                "api.example.com",
                "/down",
                null,
                20,
                false,
                "Connection refused",
                "RestTemplate",
                Map.of(),
                "main");
        RestClientTraceController controller = controller(recorder, ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.stats().totalCalls()).isEqualTo(2);
        assertThat(report.stats().slowCalls()).isEqualTo(1);
        assertThat(report.stats().failedCalls()).isEqualTo(1);
        assertThat(report.entries())
                .anySatisfy(entry -> assertThat(entry.slow()).isTrue());
        assertThat(report.entries())
                .anySatisfy(entry -> assertThat(entry.success()).isFalse());
    }

    @Test
    void capturesRequestHeadersWhenEnabled() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, true);
        recorder.record(
                "GET",
                "https://api.example.com/orders",
                "api.example.com",
                "/orders",
                200,
                10,
                true,
                null,
                "RestTemplate",
                Map.of("X-Request-Id", "abc-123"),
                "main");
        RestClientTraceController controller = controller(recorder, ValueExposure.MASKED);

        RestClientTraceReport report = controller.trace();
        assertThat(report.captureHeaders()).isTrue();
        assertThat(report.entries().get(0).requestHeaders()).containsEntry("X-Request-Id", "abc-123");
        assertThat(report.warnings()).anyMatch(w -> w.contains("headers are captured"));
    }

    @Test
    void suppressesQueryStringAndHeadersUnderMetadataOnlyExposure() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, true);
        recorder.record(
                "GET",
                "https://api.example.com/orders?status=open",
                "api.example.com",
                "/orders",
                200,
                10,
                true,
                null,
                "RestTemplate",
                Map.of("X-Request-Id", "abc-123"),
                "main");
        RestClientTraceController controller = controller(recorder, ValueExposure.METADATA_ONLY);

        RestClientTraceReport report = controller.trace();
        assertThat(report.entries().get(0).uri()).doesNotContain("status=open");
        assertThat(report.entries().get(0).requestHeaders()).containsEntry("X-Request-Id", "");
    }

    @Test
    void clearEmptiesTheBuffer() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, false);
        recorder.record(
                "GET",
                "https://api.example.com/orders",
                "api.example.com",
                "/orders",
                200,
                10,
                true,
                null,
                "RestTemplate",
                Map.of(),
                "main");
        RestClientTraceController controller = controller(recorder, ValueExposure.MASKED);

        RestClientTraceReport report = controller.clear();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void recordingTogglePausesAndResumes() {
        RestClientTraceRecorder recorder = instrumentedRecorder(true, false);
        RestClientTraceController controller = controller(recorder, ValueExposure.MASKED);

        RestClientTraceReport paused = controller.recording(new RestClientTraceRecordingRequest(false));
        assertThat(paused.capturing()).isFalse();
        assertThat(paused.warnings()).anyMatch(w -> w.contains("paused"));

        RestClientTraceReport toggled = controller.recording(null);
        assertThat(toggled.capturing()).isTrue();
    }
}
