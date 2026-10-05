package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.WebSocketActivityEntryDto;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Pins the STOMP-level observation: subscriptions are tracked, destinations (structural routing metadata,
 * like an HTTP path) are recorded, message bodies are not, and inbound frames are never double counted.
 */
class BootUiStompChannelInterceptorTests {

    private final WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
    private final BootUiWebSocketSessionRegistry registry =
            new BootUiWebSocketSessionRegistry(WebSocketSettings.defaults());

    @BeforeEach
    void openSession() {
        registry.opened(new WebSocketSessionSnapshot(
                "s1", "stomp:/ws", "/ws", true, System.currentTimeMillis(), "v12.stomp", null, null, null));
    }

    private BootUiStompChannelInterceptor interceptor(WebSocketActivityRecorder.Direction direction) {
        return new BootUiStompChannelInterceptor(recorder, registry, direction);
    }

    /** Drives the full interceptor contract, because the frame is recorded once delivery has completed. */
    private void send(WebSocketActivityRecorder.Direction direction, Message<?> message) {
        BootUiStompChannelInterceptor interceptor = interceptor(direction);
        interceptor.preSend(message, null);
        interceptor.afterSendCompletion(message, null, true, null);
    }

    private static Message<byte[]> stomp(
            StompCommand command, String sessionId, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(false);
        return MessageBuilder.createMessage("body".getBytes(), accessor.getMessageHeaders());
    }

    private WebSocketReport report() {
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
                List.of("/topic", "/queue"),
                List.of("/app"),
                "/user",
                true,
                null);
        ExposurePolicy exposure = new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.FULL;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
        return new WebSocketService(() -> topology, registry, recorder, WebSocketSettings.defaults(), exposure)
                .report();
    }

    @Test
    void tracksSubscribeAndUnsubscribeAgainstTheOwningSession() {
        send(WebSocketActivityRecorder.Direction.INBOUND, stomp(StompCommand.SUBSCRIBE, "s1", "sub-0", "/topic/chat"));

        assertThat(registry.subscriptions()).hasSize(1);
        assertThat(registry.subscriptions().get(0).destination()).isEqualTo("/topic/chat");
        assertThat(registry.subscriptions().get(0).endpointId()).isEqualTo("stomp:/ws");

        send(WebSocketActivityRecorder.Direction.INBOUND, stomp(StompCommand.UNSUBSCRIBE, "s1", "sub-0", null));
        assertThat(registry.subscriptions()).isEmpty();
    }

    @Test
    void recordsDestinationsAndFrameTypesButNeverTheBody() {
        send(WebSocketActivityRecorder.Direction.INBOUND, stomp(StompCommand.CONNECT, "s1", null, null));
        send(WebSocketActivityRecorder.Direction.INBOUND, stomp(StompCommand.SUBSCRIBE, "s1", "sub-0", "/topic/chat"));
        send(WebSocketActivityRecorder.Direction.OUTBOUND, stomp(StompCommand.MESSAGE, "s1", "sub-0", "/topic/chat"));

        List<WebSocketActivityEntryDto> activity = report().activity();
        assertThat(activity).extracting(WebSocketActivityEntryDto::frameType).contains("CONNECT", "SUBSCRIBE", "TEXT");
        assertThat(activity)
                .as("only the already-known body length is recorded, never any of its content")
                .filteredOn(entry -> "TEXT".equals(entry.frameType()))
                .allMatch(entry -> entry.payloadBytes() != null && entry.payloadBytes() == 4L);
        assertThat(activity)
                .filteredOn(entry -> "TEXT".equals(entry.frameType()))
                .allMatch(entry -> "/topic/chat".equals(entry.destination()));
    }

    @Test
    void recordsInboundSendFramesOnceWithTheirDestination() {
        send(WebSocketActivityRecorder.Direction.INBOUND, stomp(StompCommand.SEND, "s1", null, "/app/chat"));

        assertThat(report().activity())
                .as("the transport decorator leaves data frames to this interceptor, which knows the destination")
                .hasSize(1);
        assertThat(report().activity().get(0).destination()).isEqualTo("/app/chat");
        assertThat(report().stats().inboundFrames()).isEqualTo(1);
    }

    @Test
    void neverSerializesTheRawSessionIdSpringEmbedsInAResolvedUserDestination() {
        // DefaultUserDestinationResolver rewrites /user/{name}/queue/x into /queue/x-user<simpSessionId>, so
        // the destination carries verbatim the identifier BootUI hashes everywhere else.
        send(
                WebSocketActivityRecorder.Direction.OUTBOUND,
                stomp(StompCommand.MESSAGE, "s1", "sub-0", "/queue/notifications-users1"));

        WebSocketActivityEntryDto entry = report().activity().get(0);
        assertThat(entry.destination())
                .as("a live, addressable session id must never reach the JSON contract")
                .doesNotContain("-users1");
        assertThat(entry.destination()).startsWith("/queue/notifications-user");
    }

    @Test
    void recordsTheFailureCategoryButNeverTheExceptionMessage() {
        Message<byte[]> message = stomp(StompCommand.SEND, "s1", null, "/app/chat");
        BootUiStompChannelInterceptor interceptor = interceptor(WebSocketActivityRecorder.Direction.INBOUND);
        interceptor.preSend(message, null);
        interceptor.afterSendCompletion(
                message, null, false, new IllegalStateException("payload {\"secret\":\"hunter2\"} rejected"));

        WebSocketActivityEntryDto entry = report().activity().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorCategory()).isEqualTo("IllegalStateException");
        assertThat(report().stats().failedFrames()).isEqualTo(1);
    }

    @Test
    void ignoresMessagesWithoutAStompMessageType() {
        Message<byte[]> plain = MessageBuilder.withPayload("body".getBytes()).build();

        assertThatCode(() -> send(WebSocketActivityRecorder.Direction.INBOUND, plain))
                .doesNotThrowAnyException();
        assertThat(report().activity()).isEmpty();
    }

    @Test
    void returnsTheOriginalMessageUnchangedAndSurvivesBookkeepingFailures() {
        BootUiWebSocketSessionRegistry exploding = new BootUiWebSocketSessionRegistry(WebSocketSettings.defaults()) {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                throw new IllegalStateException("boom");
            }
        };
        Message<byte[]> message = stomp(StompCommand.SUBSCRIBE, "s1", "sub-0", "/topic/chat");
        BootUiStompChannelInterceptor interceptor =
                new BootUiStompChannelInterceptor(recorder, exploding, WebSocketActivityRecorder.Direction.INBOUND);

        assertThat(interceptor.preSend(message, null))
                .as("the interceptor observes; it never rewrites or drops a message")
                .isSameAs(message);
    }

    @Test
    void mapsBothConnectAndConnectAckToTheConnectFrameType() {
        SimpMessageHeaderAccessor ack = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        ack.setSessionId("s1");
        send(
                WebSocketActivityRecorder.Direction.OUTBOUND,
                MessageBuilder.createMessage(new byte[0], ack.getMessageHeaders()));

        assertThat(report().activity())
                .extracting(WebSocketActivityEntryDto::frameType)
                .containsExactly("CONNECT");
    }
}
