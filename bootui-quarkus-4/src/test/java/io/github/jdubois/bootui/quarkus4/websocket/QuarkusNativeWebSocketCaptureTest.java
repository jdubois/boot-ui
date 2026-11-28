package io.github.jdubois.bootui.quarkus4.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

class QuarkusNativeWebSocketCaptureTest {

    @Test
    void denialRetainsNoConnectionStateAndProceedsWithoutMessageCapture() throws Exception {
        Instance<WebSocketActivityRecorder> recorders = instance();
        Instance<WebSocketSettings> settings = instance();
        InvocationContext invocation = mock(InvocationContext.class);
        Object result = new Object();
        when(invocation.proceed()).thenReturn(result);
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            QuarkusWebSocketConnectionCapture capture = new QuarkusWebSocketConnectionCapture(recorders, settings);
            capture.onOpen(null);
            capture.onClose(null);
            assertThat(capture.closedConnections()).isEmpty();
            assertThat(new QuarkusWebSocketMessageInterceptor().aroundMessage(invocation))
                    .isSameAs(result);
            verify(invocation).proceed();
            verifyNoMoreInteractions(invocation);
            verifyNoInteractions(recorders, settings);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance() {
        return mock(Instance.class);
    }
}
