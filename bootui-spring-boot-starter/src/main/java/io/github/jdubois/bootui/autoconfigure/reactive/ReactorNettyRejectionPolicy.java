package io.github.jdubois.bootui.autoconfigure.reactive;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.netty.http.server.HttpServerResponse;

/** Loaded only when Reactor Netty is present; other WebFlux server implementations are unaffected. */
final class ReactorNettyRejectionPolicy {

    private static final Log logger = LogFactory.getLog(ReactorNettyRejectionPolicy.class);

    private ReactorNettyRejectionPolicy() {}

    static void closeHttp1Connection(ServerHttpResponse response) {
        ServerHttpResponse delegate = response;
        while (delegate instanceof ServerHttpResponseDecorator decorator) {
            delegate = decorator.getDelegate();
        }
        if (!(delegate instanceof AbstractServerHttpResponse serverResponse)) {
            return;
        }
        Object nativeResponse;
        try {
            nativeResponse = serverResponse.getNativeResponse();
        } catch (IllegalStateException | UnsupportedOperationException noNativeResponse) {
            logger.debug("No native response available; retaining the server's connection handling", noNativeResponse);
            return;
        }
        if (nativeResponse instanceof HttpServerResponse nettyResponse
                && nettyResponse.version().majorVersion() == 1) {
            // An early synchronous rejection can strand queued keep-alive requests in Reactor Netty 1.3.7
            // (reactor/reactor-netty#4361). Close after the response without draining an untrusted body.
            response.getHeaders().set(HttpHeaders.CONNECTION, "close");
        }
    }
}
