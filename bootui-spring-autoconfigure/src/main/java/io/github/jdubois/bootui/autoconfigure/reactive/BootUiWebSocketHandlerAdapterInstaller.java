package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * Puts {@link BootUiWebSocketHandlerAdapter} in the place of WebFlux's own {@link WebSocketHandlerAdapter}, keeping its
 * {@code WebSocketService} and order (M4-10). An adapter the application subclassed is left untouched, since BootUI
 * cannot carry over what the subclass changes.
 */
public final class BootUiWebSocketHandlerAdapterInstaller implements BeanPostProcessor {

    private final ObjectProvider<WebSocketActivityRecorder> recorder;

    public BootUiWebSocketHandlerAdapterInstaller(ObjectProvider<WebSocketActivityRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean.getClass() == WebSocketHandlerAdapter.class) {
            return new BootUiWebSocketHandlerAdapter((WebSocketHandlerAdapter) bean, recorder);
        }
        return bean;
    }
}
