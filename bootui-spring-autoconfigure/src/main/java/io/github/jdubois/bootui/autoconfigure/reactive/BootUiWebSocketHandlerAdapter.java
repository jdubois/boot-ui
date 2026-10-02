package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * WebFlux's {@link WebSocketHandlerAdapter}, which makes each message an application's {@link WebSocketHandler}
 * receives an execution of its own ({@code docs/PLAN-v2.md} §5.18, M4-10).
 *
 * <p>WebFlux has no interception seam for messages, so BootUI takes the framework's own adapter's place, keeping its
 * {@code WebSocketService} and order, and hands each handler a session whose {@link WebSocketSession#receive()}
 * opens a correlation scope with a new execution id around the delivery of each message. The work the handler does
 * synchronously for that message, such as a blocking query in a {@code map}, nests under it; work it moves to another
 * scheduler joins the context Reactor restores there instead. Each message is then published to the runtime journal
 * with the handler mapping's pattern as its destination and its size, never its content.</p>
 */
public class BootUiWebSocketHandlerAdapter extends WebSocketHandlerAdapter {

    private final ObjectProvider<WebSocketActivityRecorder> recorder;

    public BootUiWebSocketHandlerAdapter(
            WebSocketHandlerAdapter original, ObjectProvider<WebSocketActivityRecorder> recorder) {
        super(original.getWebSocketService());
        setOrder(original.getOrder());
        this.recorder = recorder;
    }

    @Override
    public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
        Object observed = handler;
        try {
            WebSocketActivityRecorder messages = recorder.getIfAvailable();
            if (handler instanceof WebSocketHandler webSocket && messages != null && messages.isEnabled()) {
                String destination = destination(exchange);
                observed = new ExecutionHandler(webSocket, "handler:" + destination, destination, messages);
            }
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
        return super.handle(exchange, observed);
    }

    /** The handler mapping's pattern, such as {@code /ws/{room}}, or the request path when no pattern is known. */
    private static String destination(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof PathPattern path) {
            return path.getPatternString();
        }
        if (pattern instanceof String value && !value.isBlank()) {
            return value;
        }
        return exchange.getRequest().getPath().pathWithinApplication().value();
    }

    /** The application's handler, given a session whose received messages are executions. */
    record ExecutionHandler(
            WebSocketHandler delegate, String endpoint, String destination, WebSocketActivityRecorder recorder)
            implements WebSocketHandler {

        @Override
        public List<String> getSubProtocols() {
            return delegate.getSubProtocols();
        }

        @Override
        public Mono<Void> handle(WebSocketSession session) {
            return delegate.handle(new ExecutionSession(session, this));
        }
    }

    /** A session that opens one execution per received message, delegating everything else. */
    record ExecutionSession(WebSocketSession delegate, ExecutionHandler handler) implements WebSocketSession {

        @Override
        public Flux<WebSocketMessage> receive() {
            Function<? super Publisher<WebSocketMessage>, ? extends Publisher<WebSocketMessage>> lift =
                    Operators.lift((scannable, actual) -> new ExecutionSubscriber(actual, handler));
            return delegate.receive().transform(lift);
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return delegate.getHandshakeInfo();
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return delegate.bufferFactory();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return delegate.send(messages);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public Mono<Void> close() {
            return delegate.close();
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            return delegate.close(status);
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return delegate.closeStatus();
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return delegate.textMessage(payload);
        }

        @Override
        public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return delegate.binaryMessage(payloadFactory);
        }

        @Override
        public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return delegate.pingMessage(payloadFactory);
        }

        @Override
        public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return delegate.pongMessage(payloadFactory);
        }
    }

    /** Delivers each data message inside an execution of its own, then publishes it. */
    static final class ExecutionSubscriber implements CoreSubscriber<WebSocketMessage> {

        private final CoreSubscriber<? super WebSocketMessage> actual;
        private final ExecutionHandler handler;

        ExecutionSubscriber(CoreSubscriber<? super WebSocketMessage> actual, ExecutionHandler handler) {
            this.actual = actual;
            this.handler = handler;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            actual.onSubscribe(subscription);
        }

        @Override
        public void onNext(WebSocketMessage message) {
            WebSocketMessage.Type type = message.getType();
            if (type != WebSocketMessage.Type.TEXT && type != WebSocketMessage.Type.BINARY) {
                actual.onNext(message);
                return;
            }
            Long bytes = payloadBytes(message);
            CorrelationContext context = CorrelationContext.forExecution(RequestIds.next());
            long start = System.nanoTime();
            boolean failed = false;
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(context)) {
                actual.onNext(message);
            } catch (RuntimeException | Error ex) {
                failed = true;
                throw ex;
            } finally {
                try {
                    handler.recorder()
                            .recordHandledMessage(
                                    handler.endpoint(),
                                    handler.destination(),
                                    bytes,
                                    Math.max(0, System.nanoTime() - start),
                                    failed,
                                    context);
                } catch (RuntimeException ignored) {
                    // Observation must never break the application's WebSocket handling.
                }
            }
        }

        @Override
        public void onError(Throwable error) {
            actual.onError(error);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        /** The payload's size, read before delivery since a handler may release the buffer. Never its content. */
        private static @Nullable Long payloadBytes(WebSocketMessage message) {
            try {
                return (long) message.getPayload().readableByteCount();
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
