package io.github.jdubois.bootui.quarkus4.websocket;

import io.quarkus.runtime.annotations.RecordableConstructor;
import java.util.List;

/**
 * One {@code @WebSocket} endpoint declared by the application, captured at <em>build time</em> by the
 * deployment processor from the Jandex index and replayed into the runtime through
 * {@link WebSocketsRecorder}.
 *
 * <p>Build-time capture is used for the same reason the Mappings and Scheduled Tasks panels use it:
 * WebSockets Next has no runtime API that enumerates declared endpoints with their callbacks, and reading
 * the index avoids a build-step cycle with bean registration. BootUI's own endpoints are never captured
 * because BootUI declares none.</p>
 *
 * @param id the endpoint id ({@code @WebSocket#endpointId}, defaulting to the endpoint class FQN)
 * @param path the declared endpoint path
 * @param handlerClass fully-qualified name of the endpoint class
 * @param inboundProcessingMode the declared {@code InboundProcessingMode}, or {@code null} when defaulted
 * @param callbacks the endpoint's declared callback methods
 */
public record RawWebSocketEndpoint(
        String id,
        String path,
        String handlerClass,
        String inboundProcessingMode,
        List<RawWebSocketCallback> callbacks) {

    @RecordableConstructor
    public RawWebSocketEndpoint(
            String id,
            String path,
            String handlerClass,
            String inboundProcessingMode,
            List<RawWebSocketCallback> callbacks) {
        this.id = id;
        this.path = path;
        this.handlerClass = handlerClass;
        this.inboundProcessingMode = inboundProcessingMode;
        this.callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }
}
