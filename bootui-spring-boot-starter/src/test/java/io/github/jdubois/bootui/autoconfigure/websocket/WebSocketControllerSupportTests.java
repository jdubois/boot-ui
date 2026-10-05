package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the shared servlet/reactive request handling: an honest unavailable report when the backend is not
 * wired, a clear that only drops BootUI's own history, and a capture toggle that never touches the
 * application.
 */
class WebSocketControllerSupportTests {

    private final WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
    private final BootUiWebSocketSessionRegistry registry =
            new BootUiWebSocketSessionRegistry(WebSocketSettings.defaults());

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private WebSocketService service() {
        WebSocketTopologySnapshot topology = new WebSocketTopologySnapshot(
                "spring-websocket",
                List.of(new WebSocketEndpointSnapshot(
                        "stomp:/ws",
                        "/ws",
                        "STOMP",
                        "Handler",
                        List.of(),
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        true)),
                List.of("/topic"),
                List.of("/app"),
                "/user",
                true,
                null);
        ExposurePolicy exposure = new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.MASKED;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
        return new WebSocketService(() -> topology, registry, recorder, WebSocketSettings.defaults(), exposure);
    }

    private void recordSomeActivity() {
        registry.opened(new WebSocketSessionSnapshot("live", "stomp:/ws", "/ws", true, 1L, null, null, null, null));
        registry.opened(new WebSocketSessionSnapshot("gone", "stomp:/ws", "/ws", true, 2L, null, null, null, null));
        registry.closed("gone", 1000);
        recorder.recordFrame(
                "stomp:/ws",
                "live",
                WebSocketActivityRecorder.Direction.INBOUND,
                WebSocketActivityRecorder.FrameType.TEXT,
                "/app/chat",
                12L,
                null,
                true,
                null);
    }

    @Test
    void reportsUnavailableWhenTheBackendIsNotWired() {
        WebSocketReport report = WebSocketControllerSupport.report(provider(null));

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("WebSocket support is not configured");
        assertThat(report.endpoints()).isEmpty();
        assertThat(WebSocketControllerSupport.clear(provider(null)).available()).isFalse();
        assertThat(WebSocketControllerSupport.capture(provider(null), provider(recorder), null)
                        .available())
                .isFalse();
    }

    @Test
    void clearDropsRetainedActivityAndClosedHistoryButKeepsLiveSessions() {
        recordSomeActivity();
        assertThat(service().report().activity()).isNotEmpty();

        WebSocketReport report = WebSocketControllerSupport.clear(provider(service()));

        assertThat(report.activity()).isEmpty();
        assertThat(report.stats().capturedActivity())
                .as("lifetime totals survive a clear so the developer keeps the 'it did happen' signal")
                .isEqualTo(1);
        assertThat(report.sessions())
                .as("clearing history must not make a live connection disappear")
                .hasSize(1);
        assertThat(report.sessions().get(0).open()).isTrue();
    }

    @Test
    void captureTogglesWhenTheBodyIsOmittedAndObeysAnExplicitFlag() {
        assertThat(recorder.isCapturing()).isTrue();

        WebSocketReport paused = WebSocketControllerSupport.capture(provider(service()), provider(recorder), null);
        assertThat(recorder.isCapturing()).isFalse();
        assertThat(paused.capturing()).isFalse();

        WebSocketControllerSupport.capture(provider(service()), provider(recorder), new WebSocketCaptureRequest(true));
        assertThat(recorder.isCapturing()).isTrue();

        WebSocketControllerSupport.capture(provider(service()), provider(recorder), new WebSocketCaptureRequest(false));
        assertThat(recorder.isCapturing()).isFalse();
    }

    @Test
    void pausedCaptureStopsRecordingWithoutDroppingWhatWasAlreadySeen() {
        recordSomeActivity();
        WebSocketControllerSupport.capture(provider(service()), provider(recorder), new WebSocketCaptureRequest(false));

        recorder.recordFrame(
                "stomp:/ws",
                "live",
                WebSocketActivityRecorder.Direction.INBOUND,
                WebSocketActivityRecorder.FrameType.TEXT,
                "/app/chat",
                7L,
                null,
                true,
                null);

        WebSocketReport report = service().report();
        assertThat(report.activity())
                .as("pausing stops new capture but never discards the existing buffer")
                .hasSize(1);
        assertThat(report.sessions()).hasSize(2);
    }
}
