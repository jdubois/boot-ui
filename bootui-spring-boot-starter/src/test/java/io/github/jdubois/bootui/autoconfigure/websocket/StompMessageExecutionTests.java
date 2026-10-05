package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Controller;

/**
 * Pins M4-10 on Spring MVC: an inbound STOMP message a {@code @MessageMapping} handles becomes an execution, with its
 * destination as the mapping's template, and nothing else opens one.
 */
class StompMessageExecutionTests {

    private final List<RuntimeEvent> journal = new ArrayList<>();
    private final WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
    private final BootUiWebSocketSessionRegistry registry =
            new BootUiWebSocketSessionRegistry(WebSocketSettings.defaults());
    private final BootUiStompChannelInterceptor interceptor =
            new BootUiStompChannelInterceptor(recorder, registry, WebSocketActivityRecorder.Direction.INBOUND);
    private GenericApplicationContext context;
    private SimpAnnotationMethodMessageHandler handler;

    @Controller
    static class ChatController {

        @MessageMapping("/chat/{room}")
        void chat(String text) {}

        @MessageMapping("/chat/general")
        void general(String text) {}

        @SubscribeMapping("/rooms")
        List<String> rooms() {
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        recorder.setRuntimeEventSink(event -> journal.add(event));
        registry.opened(new WebSocketSessionSnapshot(
                "s1", "stomp:/ws", "/ws", true, System.currentTimeMillis(), "v12.stomp", null, null, null));
        context = new GenericApplicationContext();
        context.registerBean(ChatController.class);
        context.refresh();
        handler = new SimpAnnotationMethodMessageHandler(
                new ExecutorSubscribableChannel(),
                new ExecutorSubscribableChannel(),
                new SimpMessagingTemplate(new ExecutorSubscribableChannel()));
        handler.setApplicationContext(context);
        handler.setDestinationPrefixes(List.of("/app"));
        handler.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void destinationsResolveToTheTemplateOfTheMappingThatHandlesThem() {
        assertThat(StompDestinationTemplates.resolve(handler, SimpMessageType.MESSAGE, "/app/chat/42"))
                .isEqualTo("/app/chat/{room}");
        assertThat(StompDestinationTemplates.resolve(handler, SimpMessageType.MESSAGE, "/app/chat/general"))
                .as("the most specific mapping wins, as Spring dispatches it")
                .isEqualTo("/app/chat/general");
        assertThat(StompDestinationTemplates.resolve(handler, SimpMessageType.SUBSCRIBE, "/app/rooms"))
                .isEqualTo("/app/rooms");
        assertThat(StompDestinationTemplates.resolve(handler, SimpMessageType.MESSAGE, "/app/rooms"))
                .as("a subscribe mapping does not handle a SEND")
                .isNull();
        assertThat(StompDestinationTemplates.resolve(handler, SimpMessageType.MESSAGE, "/topic/chat"))
                .as("a broker destination runs no application code")
                .isNull();
    }

    @Test
    void anApplicationMessageIsAnExecutionThatOwnsTheWorkItsHandlerDoes() {
        Message<byte[]> message = stomp(StompCommand.SEND, "/app/chat/42");
        AtomicReference<CorrelationContext> inside = new AtomicReference<>();

        interceptor.beforeHandle(message, null, handler);
        inside.set(BootUiCorrelation.current());
        interceptor.afterMessageHandled(message, null, handler, null);

        assertThat(inside.get().executionId()).isNotNull();
        assertThat(inside.get().requestId()).isNull();
        assertThat(BootUiCorrelation.current())
                .as("the scope is closed once the handler returns")
                .isNotSameAs(inside.get());
        assertThat(journal).hasSize(1);
        RuntimeEvent event = journal.get(0);
        assertThat(event.source()).isEqualTo(JournalSource.WEBSOCKET);
        assertThat(event.executionId()).isEqualTo(inside.get().executionId());
        assertThat(event.payload())
                .isEqualTo(new WebSocketPayload(
                        "stomp:/ws", WebSocketPayload.MESSAGE, true, "/app/chat/{room}", 4, null, false));
    }

    @Test
    void aFailedHandlerIsAFailedExecution() {
        Message<byte[]> message = stomp(StompCommand.SEND, "/app/chat/42");

        interceptor.beforeHandle(message, null, handler);
        interceptor.afterMessageHandled(message, null, handler, new IllegalStateException("room closed"));

        assertThat(journal).singleElement().satisfies(event -> {
            assertThat(event.failedOrSlow()).isTrue();
            assertThat(((WebSocketPayload) event.payload()).failed()).isTrue();
        });
    }

    @Test
    void theBrokerRelayAnUnmappedDestinationAndTheOutboundChannelOpenNothing() {
        Message<byte[]> relayed = stomp(StompCommand.SEND, "/topic/chat");
        interceptor.beforeHandle(relayed, null, message -> {});
        interceptor.afterMessageHandled(relayed, null, message -> {}, null);
        interceptor.beforeHandle(relayed, null, handler);
        interceptor.afterMessageHandled(relayed, null, handler, null);
        BootUiStompChannelInterceptor outbound =
                new BootUiStompChannelInterceptor(recorder, registry, WebSocketActivityRecorder.Direction.OUTBOUND);
        Message<byte[]> mapped = stomp(StompCommand.SEND, "/app/chat/42");
        outbound.beforeHandle(mapped, null, handler);
        outbound.afterMessageHandled(mapped, null, handler, null);

        assertThat(journal).isEmpty();
        assertThat(BootUiCorrelation.current().executionId()).isNull();
    }

    private static Message<byte[]> stomp(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        accessor.setDestination(destination);
        accessor.setLeaveMutable(false);
        return MessageBuilder.createMessage("body".getBytes(), accessor.getMessageHeaders());
    }
}
