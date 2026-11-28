package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.quarkus4.QuarkusPanelAvailability;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
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
 * JAX-RS resource for the WebSockets panel ({@code GET /bootui/api/websockets} plus the {@code DELETE} clear
 * and {@code POST /capture} actions). The Quarkus analogue of Spring's {@code WebSocketController}: a thin
 * transport adapter over the shared engine {@link WebSocketService}, which owns identifier hashing, the
 * exposure policy, ordering, and the independent caps, so the wire contract is byte-identical to Spring.
 *
 * <p>Quarkus WebSockets Next exposes no message-interception SPI, so this stack records connection
 * open/close events only and the report states {@code frameCaptureSupported=false} with a reason. Message
 * payloads are never captured on any stack. The state-changing endpoints are gated by
 * {@code QuarkusPanelAccessFilter} when the panel is read-only, and they only reset BootUI's own buffers —
 * live connections are never closed.</p>
 */
@Path("/bootui/api/websockets")
public class WebSocketsResource {

    /** Upper bound on simultaneous WebSockets streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final WebSocketService service;
    private final WebSocketActivityRecorder recorder;
    private final QuarkusPanelAvailability panelAvailability;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public WebSocketsResource(
            WebSocketService service, WebSocketActivityRecorder recorder, QuarkusPanelAvailability panelAvailability) {
        this.service = service;
        this.recorder = recorder;
        this.panelAvailability = panelAvailability;
    }

    /** Constructor for resource unit tests outside Arc. */
    public WebSocketsResource(WebSocketService service, WebSocketActivityRecorder recorder) {
        this(service, recorder, null);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport report() {
        return snapshot();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport clear() {
        service.clear();
        return snapshot();
    }

    @POST
    @Path("/capture")
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport capture(WebSocketCaptureRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isCapturing() : request.enabled();
        recorder.setCapturing(enabled);
        return snapshot();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, recorder::subscribe);
    }

    private WebSocketReport snapshot() {
        if (panelAvailability != null && !panelAvailability.isPanelAvailable(BootUiPanels.WEBSOCKETS)) {
            return WebSocketReport.unavailable(panelAvailability.panelUnavailableReason(BootUiPanels.WEBSOCKETS));
        }
        return service.report();
    }
}
