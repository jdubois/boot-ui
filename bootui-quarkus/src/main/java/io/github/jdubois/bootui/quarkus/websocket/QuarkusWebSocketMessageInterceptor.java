package io.github.jdubois.bootui.quarkus.websocket;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes each message a WebSockets Next endpoint's {@code @OnTextMessage} or {@code @OnBinaryMessage} method receives
 * an execution of its own ({@code docs/PLAN-v2.md} §5.18, M4-10): it opens a BootUI execution context around the
 * method, so the SQL, exceptions, and calls it makes on its thread nest under that message, and then publishes the
 * message to the runtime journal with the endpoint's path as its destination and, for a {@code byte[]} message, its
 * length. It never reads a message. A method returning a {@code Uni} or {@code Multi} is covered until it returns, not
 * while its result completes.
 *
 * <p>Ordered before platform interceptors such as {@code @Transactional}, so their work runs inside the execution. It
 * imports no {@code io.quarkus.websockets.next} type: endpoints are named from BootUI's build-time topology.</p>
 */
@BootUiWebSocketMessage
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class QuarkusWebSocketMessageInterceptor {

    @Inject
    Instance<WebSocketActivityRecorder> recorder;

    @Inject
    Instance<QuarkusWebSockets> topology;

    private final Map<String, RawWebSocketEndpoint> endpoints = new ConcurrentHashMap<>();

    @AroundInvoke
    Object aroundMessage(InvocationContext invocation) throws Exception {
        CorrelationContext context = CorrelationContext.forExecution(RequestIds.next());
        long start = System.nanoTime();
        boolean failed = false;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(context)) {
            return invocation.proceed();
        } catch (Exception | Error ex) {
            failed = true;
            throw ex;
        } finally {
            record(invocation, context, Math.max(0, System.nanoTime() - start), failed);
        }
    }

    private void record(InvocationContext invocation, CorrelationContext context, long nanos, boolean failed) {
        try {
            if (!recorder.isResolvable()) {
                return;
            }
            Class<?> type = invocation.getMethod().getDeclaringClass();
            RawWebSocketEndpoint endpoint = endpoint(type.getName());
            String path = endpoint == null || endpoint.path() == null ? type.getSimpleName() : endpoint.path();
            recorder.get()
                    .recordHandledMessage(
                            endpoint == null ? type.getName() : endpoint.id(),
                            path,
                            payloadBytes(invocation.getParameters()),
                            nanos,
                            failed,
                            context);
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
    }

    private RawWebSocketEndpoint endpoint(String handlerClass) {
        RawWebSocketEndpoint known = endpoints.get(handlerClass);
        if (known != null || !topology.isResolvable()) {
            return known;
        }
        for (RawWebSocketEndpoint endpoint : topology.get().endpoints()) {
            if (handlerClass.equals(endpoint.handlerClass())) {
                endpoints.put(handlerClass, endpoint);
                return endpoint;
            }
        }
        return null;
    }

    /** The length of a {@code byte[]} message, the only shape whose size is known without reading it. */
    private static Long payloadBytes(Object[] parameters) {
        if (parameters != null) {
            for (Object parameter : parameters) {
                if (parameter instanceof byte[] bytes) {
                    return (long) bytes.length;
                }
            }
        }
        return null;
    }
}
