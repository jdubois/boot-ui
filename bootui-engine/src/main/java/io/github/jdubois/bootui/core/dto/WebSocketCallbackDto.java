package io.github.jdubois.bootui.core.dto;

/**
 * One framework callback declared by a WebSocket endpoint.
 *
 * <p>Describes <em>which application method handles which kind of WebSocket event</em>, never a message
 * payload. On Spring this covers {@code @MessageMapping}/{@code @SubscribeMapping} destinations and the
 * {@code WebSocketHandler} lifecycle methods; on Quarkus it covers the WebSockets Next
 * {@code @OnOpen}/{@code @OnTextMessage}/{@code @OnBinaryMessage}/{@code @OnPingMessage}/
 * {@code @OnPongMessage}/{@code @OnClose}/{@code @OnError} callbacks captured at build time.</p>
 *
 * @param type callback kind, one of {@code ON_OPEN}, {@code ON_MESSAGE}, {@code ON_BINARY_MESSAGE},
 *     {@code ON_PING}, {@code ON_PONG}, {@code ON_CLOSE}, {@code ON_ERROR}, {@code MESSAGE_MAPPING},
 *     {@code SUBSCRIBE_MAPPING}, or {@code EXCEPTION_HANDLER}
 * @param destination the declared destination for a message mapping, or {@code null} when the callback is
 *     a lifecycle hook rather than a routed destination
 * @param declaringClass the application class declaring the callback
 * @param method the callback method name, without parameters
 * @param messageType the declared message type the callback accepts (for example {@code String} or
 *     {@code Buffer}), or {@code null} when the framework does not expose one
 */
public record WebSocketCallbackDto(
        String type, String destination, String declaringClass, String method, String messageType) {}
