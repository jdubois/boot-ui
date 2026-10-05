package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import org.springframework.beans.factory.ObjectProvider;

/** Shared request handling for the servlet and reactive WebSockets controllers. */
public final class WebSocketControllerSupport {

    static final String NOT_CONFIGURED = "WebSocket support is not configured";

    private WebSocketControllerSupport() {}

    public static WebSocketReport report(ObjectProvider<WebSocketService> serviceProvider) {
        WebSocketService service = serviceProvider.getIfAvailable();
        return service == null ? WebSocketReport.unavailable(NOT_CONFIGURED) : service.report();
    }

    /**
     * Drops BootUI's retained activity, per-session counters, and closed-session history. Sessions that are
     * still open remain visible; live application sessions and subscriptions are never closed, cancelled, or
     * otherwise disturbed.
     */
    public static WebSocketReport clear(ObjectProvider<WebSocketService> serviceProvider) {
        WebSocketService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return WebSocketReport.unavailable(NOT_CONFIGURED);
        }
        service.clear();
        return service.report();
    }

    /** Pauses or resumes activity capture; omitting the body toggles the current state. */
    public static WebSocketReport capture(
            ObjectProvider<WebSocketService> serviceProvider,
            ObjectProvider<WebSocketActivityRecorder> recorderProvider,
            WebSocketCaptureRequest request) {
        WebSocketService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return WebSocketReport.unavailable(NOT_CONFIGURED);
        }
        WebSocketActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            boolean enabled =
                    (request == null || request.enabled() == null) ? !recorder.isCapturing() : request.enabled();
            recorder.setCapturing(enabled);
        }
        return service.report();
    }
}
