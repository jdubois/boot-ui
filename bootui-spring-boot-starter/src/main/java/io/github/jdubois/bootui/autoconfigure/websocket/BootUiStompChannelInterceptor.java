package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

/**
 * Records STOMP-level activity metadata on the client inbound and outbound channels.
 *
 * <p>The raw transport decorator sees WebSocket frames but not STOMP semantics, so it records session
 * lifecycle and control frames only and leaves every data frame to this interceptor, which records it once
 * with its destination and command. That split means an inbound application message is counted exactly
 * once, and always with the routing metadata a developer actually needs.</p>
 *
 * <p>Destinations are structural routing metadata, exactly like an HTTP path, and are the primary thing a
 * developer needs to debug a STOMP application. The message body is never read: only the payload's
 * already-known length is recorded, and nothing else from the payload is retained. Native headers,
 * principals, and session attributes are ignored.</p>
 *
 * <p>On the inbound channel it also makes each application message an execution ({@code docs/PLAN-v2.md} §5.18,
 * M4-10): around the {@code @MessageMapping} or {@code @SubscribeMapping} method that handles it, on the channel's own
 * executor thread, it opens a correlation scope with a new execution id, so the SQL, exceptions, and calls the handler
 * makes nest under that message, and it then publishes the message to the runtime journal with its destination as
 * the mapping's template. The broker's own relay of a message opens nothing, and neither does a message no mapping
 * handles.</p>
 */
public class BootUiStompChannelInterceptor implements ExecutorChannelInterceptor {

    private final WebSocketActivityRecorder recorder;
    private final BootUiWebSocketSessionRegistry registry;
    private final WebSocketActivityRecorder.Direction direction;

    public BootUiStompChannelInterceptor(
            WebSocketActivityRecorder recorder,
            BootUiWebSocketSessionRegistry registry,
            WebSocketActivityRecorder.Direction direction) {
        this.recorder = recorder;
        this.registry = registry;
        this.direction = direction;
    }

    private final ThreadLocal<Long> startedAt = new BootUiThreadLocal<>();
    private final ThreadLocal<HandledMessage> handling = new BootUiThreadLocal<>();
    private final StompDestinationTemplates templates = new StompDestinationTemplates();

    /** The execution a handler is running for: its scope, its destination template, and when it started. */
    private record HandledMessage(
            BootUiCorrelation.Scope scope, CorrelationContext context, String template, long startNanos) {}

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            startedAt.set(System.nanoTime());
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        }
        return message;
    }

    /**
     * Records the frame once delivery has completed, so the entry carries how long the application's own
     * dispatch took and whether it failed. Spring invokes this on the same thread that ran {@code preSend}.
     *
     * <p>Only the exception's type is recorded. An exception message can quote the payload it failed on, and
     * this panel never lets message content reach BootUI's memory or JSON.</p>
     */
    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        Long started = startedAt.get();
        startedAt.remove();
        try {
            Long durationMillis = started == null ? null : Math.max(0, (System.nanoTime() - started) / 1_000_000);
            record(
                    message,
                    durationMillis,
                    ex == null && sent,
                    ex == null ? null : ex.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        }
    }

    /**
     * Opens the execution of an application message, on the thread that runs its {@code @MessageMapping} or
     * {@code @SubscribeMapping} method.
     */
    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        try {
            if (direction != WebSocketActivityRecorder.Direction.INBOUND
                    || !(handler instanceof SimpAnnotationMethodMessageHandler annotated)) {
                return message;
            }
            SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
            if (type != SimpMessageType.MESSAGE && type != SimpMessageType.SUBSCRIBE) {
                return message;
            }
            String template =
                    templates.template(annotated, type, SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
            if (template == null) {
                return message;
            }
            CorrelationContext context = CorrelationContext.forExecution(RequestIds.next());
            handling.set(new HandledMessage(BootUiCorrelation.open(context), context, template, System.nanoTime()));
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        }
        return message;
    }

    /** Closes the execution {@link #beforeHandle} opened and publishes the message it ran for. */
    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        HandledMessage handled = handling.get();
        if (handled == null) {
            return;
        }
        handling.remove();
        try {
            recorder.recordHandledMessage(
                    registry.endpointIdFor(SimpMessageHeaderAccessor.getSessionId(message.getHeaders())),
                    handled.template(),
                    payloadBytes(message),
                    Math.max(0, System.nanoTime() - handled.startNanos()),
                    ex != null,
                    handled.context());
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        } finally {
            handled.scope().close();
        }
    }

    private void record(Message<?> message, Long durationMillis, boolean success, String errorCategory) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        SimpMessageType messageType = accessor.getMessageType();
        if (messageType == null) {
            return;
        }
        String sessionId = accessor.getSessionId();
        String destination = accessor.getDestination();
        String endpointId = registry.endpointIdFor(sessionId);
        WebSocketActivityRecorder.FrameType frameType =
                switch (messageType) {
                    case CONNECT, CONNECT_ACK -> WebSocketActivityRecorder.FrameType.CONNECT;
                    case SUBSCRIBE -> WebSocketActivityRecorder.FrameType.SUBSCRIBE;
                    case UNSUBSCRIBE -> WebSocketActivityRecorder.FrameType.UNSUBSCRIBE;
                    case DISCONNECT -> WebSocketActivityRecorder.FrameType.CLOSE;
                    case HEARTBEAT -> WebSocketActivityRecorder.FrameType.PING;
                    default -> WebSocketActivityRecorder.FrameType.TEXT;
                };
        if (messageType == SimpMessageType.SUBSCRIBE) {
            registry.subscribed(sessionId, accessor.getSubscriptionId(), endpointId, destination);
        } else if (messageType == SimpMessageType.UNSUBSCRIBE) {
            registry.unsubscribed(sessionId, accessor.getSubscriptionId());
        }
        recorder.recordFrame(
                endpointId,
                sessionId,
                direction,
                frameType,
                destination,
                payloadBytes(message),
                durationMillis,
                success,
                errorCategory);
    }

    /**
     * Returns the payload size in bytes without reading the payload's content. Only the array length is
     * touched; the bytes themselves are never inspected, copied, or retained. A payload that has already
     * been converted to text reports no size rather than a character count, because the contract documents
     * bytes and encoding a string to measure it would mean copying the message body.
     */
    private Long payloadBytes(Message<?> message) {
        Object payload = message.getPayload();
        if (payload instanceof byte[] bytes) {
            return (long) bytes.length;
        }
        return null;
    }
}
