package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JAX-RS resource for the REST Client panel ({@code GET /bootui/api/rest-client-trace} plus {@code /clear}
 * and {@code /recording} actions). The Quarkus analogue of Spring's {@code RestClientTraceController}: a thin
 * transport adapter over the shared engine {@link RestClientTraceRecorder}, which owns the capped ring buffer,
 * grouping/stats/chatty-call assembly, and report shaping, so the wire contract is byte-identical to Spring.
 *
 * <p>Capture is wired via the MicroProfile {@code RestClientListener} SPI ({@link
 * io.github.jdubois.bootui.quarkus.restclienttrace.QuarkusRestClientTraceListener}), registered
 * conditionally by the deployment processor when the REST Client Reactive capability is present. The
 * recorder is always produced (so this resource always wires), but {@link RestClientTraceRecorder#hasInstrumentedClient()}
 * returns {@code false} until the listener registers at least one REST client — which is reflected in the
 * report's {@code available} flag. State-changing endpoints ({@code /clear}, {@code /recording}) are gated
 * by {@code QuarkusPanelAccessFilter} when the panel is read-only. The SSE stream ({@code /stream}) ticks on
 * every captured call, clear, or recording toggle so the Vue panel's auto-refresh toggle works identically to
 * Spring.</p>
 */
@Path("/bootui/api/rest-client-trace")
public class RestClientTraceResource {

    /** Upper bound on simultaneous REST-client-trace streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final RestClientTraceRecorder recorder;
    private final QuarkusExposurePolicy exposure;
    private final QuarkusPanelAvailability panelAvailability;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public RestClientTraceResource(
            RestClientTraceRecorder recorder,
            QuarkusExposurePolicy exposure,
            QuarkusPanelAvailability panelAvailability) {
        this.recorder = recorder;
        this.exposure = exposure;
        this.panelAvailability = panelAvailability;
    }

    /** Constructor for resource unit tests outside Arc. */
    public RestClientTraceResource(RestClientTraceRecorder recorder, QuarkusExposurePolicy exposure) {
        this(recorder, exposure, null);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport trace() {
        return report();
    }

    @POST
    @Path("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport clear() {
        recorder.clear();
        return report();
    }

    @POST
    @Path("/recording")
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport recording(RestClientTraceRecordingRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, recorder::subscribe);
    }

    private RestClientTraceReport report() {
        if (panelAvailability != null && !panelAvailability.isPanelAvailable(BootUiPanels.REST_CLIENT_TRACE)) {
            return RestClientTraceReport.unavailable(unavailableReason());
        }
        if (!recorder.isEnabled() || !recorder.hasInstrumentedClient()) {
            return RestClientTraceReport.unavailable(unavailableReason());
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure());
    }

    private String unavailableReason() {
        if (panelAvailability != null && !panelAvailability.isPanelAvailable(BootUiPanels.REST_CLIENT_TRACE)) {
            return panelAvailability.panelUnavailableReason(BootUiPanels.REST_CLIENT_TRACE);
        }
        if (!recorder.isEnabled()) {
            return QuarkusPanelAvailability.REST_CLIENT_TRACE_DISABLED;
        }
        return QuarkusPanelAvailability.REST_CLIENT_TRACE_NOT_INSTRUMENTED;
    }
}
