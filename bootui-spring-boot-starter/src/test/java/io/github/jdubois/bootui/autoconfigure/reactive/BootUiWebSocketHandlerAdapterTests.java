package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;

/** Pins M4-10 on WebFlux: each message a handler receives is an execution that owns what the handler does for it. */
class BootUiWebSocketHandlerAdapterTests {

    private final List<RuntimeEvent> journal = new ArrayList<>();
    private final WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());

    @Test
    void eachReceivedMessageIsAnExecutionThatOwnsTheHandlersSynchronousWork() {
        recorder.setRuntimeEventSink(journal::add);
        List<CorrelationContext> seen = new ArrayList<>();
        WebSocketHandler application = session -> session.receive()
                .doOnNext(message -> seen.add(BootUiCorrelation.current()))
                .then();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.receive())
                .thenReturn(Flux.just(
                        text("hello"),
                        new WebSocketMessage(
                                WebSocketMessage.Type.PING, DefaultDataBufferFactory.sharedInstance.wrap(new byte[0])),
                        text("again")));

        new BootUiWebSocketHandlerAdapter.ExecutionHandler(application, "handler:/echo", "/echo", recorder)
                .handle(session)
                .block();

        assertThat(seen).hasSize(3);
        assertThat(seen.get(0).executionId()).isNotNull();
        assertThat(seen.get(1).executionId())
                .as("a control frame runs no application message")
                .isNull();
        assertThat(seen.get(2).executionId())
                .isNotNull()
                .isNotEqualTo(seen.get(0).executionId());
        assertThat(BootUiCorrelation.current().executionId()).isNull();
        assertThat(journal)
                .extracting(RuntimeEvent::source, RuntimeEvent::executionId, RuntimeEvent::payload)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                JournalSource.WEBSOCKET,
                                seen.get(0).executionId(),
                                WebSocketPayload.handled("handler:/echo", "/echo", 5L, false)),
                        org.assertj.core.groups.Tuple.tuple(
                                JournalSource.WEBSOCKET,
                                seen.get(2).executionId(),
                                WebSocketPayload.handled("handler:/echo", "/echo", 5L, false)));
    }

    @Test
    void onlyTheFrameworksOwnAdapterIsReplacedKeepingItsOrder() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", recorder);
        BootUiWebSocketHandlerAdapterInstaller installer =
                new BootUiWebSocketHandlerAdapterInstaller(beans.getBeanProvider(WebSocketActivityRecorder.class));
        WebSocketHandlerAdapter framework = new WebSocketHandlerAdapter();
        framework.setOrder(7);
        WebSocketHandlerAdapter custom = new WebSocketHandlerAdapter() {};

        Object replaced = installer.postProcessAfterInitialization(framework, "webFluxWebSocketHandlerAdapter");

        assertThat(replaced).isInstanceOf(BootUiWebSocketHandlerAdapter.class);
        assertThat(((WebSocketHandlerAdapter) replaced).getOrder()).isEqualTo(7);
        assertThat(((WebSocketHandlerAdapter) replaced).getWebSocketService())
                .isSameAs(framework.getWebSocketService());
        assertThat(installer.postProcessAfterInitialization(custom, "custom")).isSameAs(custom);
    }

    private static WebSocketMessage text(String value) {
        return new WebSocketMessage(
                WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8)));
    }
}
