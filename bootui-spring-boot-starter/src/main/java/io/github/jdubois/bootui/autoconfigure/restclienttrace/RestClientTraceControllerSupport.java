package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import org.springframework.beans.factory.ObjectProvider;

/** Shared request handling for the servlet and reactive REST Client trace controllers. */
public final class RestClientTraceControllerSupport {

    private static final String NOT_CONFIGURED = "REST client tracing is not configured";

    private RestClientTraceControllerSupport() {}

    public static RestClientTraceReport trace(
            ObjectProvider<RestClientTraceRecorder> recorderProvider, BootUiExposure exposure) {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        return recorder == null ? RestClientTraceReport.unavailable(NOT_CONFIGURED) : report(recorder, exposure);
    }

    public static RestClientTraceReport clear(
            ObjectProvider<RestClientTraceRecorder> recorderProvider, BootUiExposure exposure) {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return RestClientTraceReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder, exposure);
    }

    public static RestClientTraceReport recording(
            ObjectProvider<RestClientTraceRecorder> recorderProvider,
            BootUiExposure exposure,
            RestClientTraceRecordingRequest request) {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return RestClientTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder, exposure);
    }

    private static RestClientTraceReport report(RestClientTraceRecorder recorder, BootUiExposure exposure) {
        if (!recorder.hasInstrumentedClient()) {
            return RestClientTraceReport.unavailable(unavailableReason(recorder));
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure());
    }

    private static String unavailableReason(RestClientTraceRecorder recorder) {
        if (!recorder.isEnabled()) {
            return "REST client tracing is disabled (set bootui.rest-client-trace.enabled=true in a "
                    + "trusted local profile).";
        }
        return "No RestClient, RestTemplate, or WebClient has been instrumented yet.";
    }
}
