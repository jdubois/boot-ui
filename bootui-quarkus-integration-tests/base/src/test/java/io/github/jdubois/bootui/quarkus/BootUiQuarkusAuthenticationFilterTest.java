package io.github.jdubois.bootui.quarkus;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiQuarkusAuthenticationFilterTest {

    private static final String TOKEN = "test-token";

    private final Config config = new SmallRyeConfigBuilder().build();
    private final BootUiQuarkusAuthenticationFilter filter =
            new BootUiQuarkusAuthenticationFilter(config, new ApiTokenAuthenticator(TOKEN));

    @Test
    void loopbackApiRequestsDoNotRequireAuthentication() {
        RoutingContext context = context(HttpMethod.GET, "127.0.0.1", null, null);

        filter.handle(context);

        verify(context).next();
    }

    @Test
    void nonLoopbackApiRequestsRequireAuthentication() {
        RoutingContext context = context(HttpMethod.GET, "10.0.0.5", null, null);

        filter.handle(context);

        verify(context, never()).next();
        verify(context.response()).setStatusCode(401);
        verify(context.response()).putHeader("WWW-Authenticate", "******"BootUI\"");
    }

    @Test
    void bearerTokenAuthenticatesNonLoopbackRequests() {
        RoutingContext context = context(HttpMethod.GET, "10.0.0.5", "Bearer " + TOKEN, null);

        filter.handle(context);

        verify(context).next();
    }

    @Test
    void sessionEndpointCreatesAnHttpOnlyCookie() {
        RoutingContext context = context(HttpMethod.POST, "10.0.0.5", "Bearer " + TOKEN, null);
        when(context.normalizedPath()).thenReturn("/bootui/api/auth/session");

        filter.handle(context);

        verify(context.response())
                .putHeader(
                        "Set-Cookie",
                        "BOOTUI_SESSION=test-token; Path=/bootui/api; HttpOnly; SameSite=Strict");
        verify(context.response()).setStatusCode(204);
    }

    private static RoutingContext context(
            HttpMethod method, String remoteAddress, String authorization, String cookie) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class, RETURNS_SELF);
        SocketAddress socketAddress = mock(SocketAddress.class);
        when(context.normalizedPath()).thenReturn("/bootui/api/overview");
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(request.method()).thenReturn(method);
        when(request.remoteAddress()).thenReturn(socketAddress);
        when(socketAddress.hostAddress()).thenReturn(remoteAddress);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getHeader("Cookie")).thenReturn(cookie);
        when(request.isSSL()).thenReturn(false);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        return context;
    }
}
