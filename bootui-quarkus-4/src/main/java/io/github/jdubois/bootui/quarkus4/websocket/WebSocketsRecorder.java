package io.github.jdubois.bootui.quarkus4.websocket;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured {@code @WebSocket} endpoint inventory into a
 * runtime {@link QuarkusWebSockets} holder, mirroring {@code MappingsRecorder} and
 * {@code ScheduledTasksRecorder}.
 */
@Recorder
public class WebSocketsRecorder {

    /** Wraps the captured endpoints in a runtime holder backing the synthetic {@link QuarkusWebSockets} bean. */
    public RuntimeValue<QuarkusWebSockets> create(List<RawWebSocketEndpoint> endpoints) {
        return new RuntimeValue<>(new QuarkusWebSockets(endpoints));
    }
}
