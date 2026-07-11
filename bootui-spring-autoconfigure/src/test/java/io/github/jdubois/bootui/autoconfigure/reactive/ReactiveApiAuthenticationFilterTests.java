package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ReactiveApiAuthenticationFilterTests {

    private static final String TOKEN = "test-token";
    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    private final ReactiveApiAuthenticationFilter filter =
            new ReactiveApiAuthenticationFilter(new BootUiProperties(), new ApiTokenAuthenticator(TOKEN));

    @Test
    void loopbackApiRequestsDoNotRequireAuthentication() {
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/overview", "127.0.0.1", null, null);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void nonLoopbackApiRequestsRequireAuthentication() {
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/overview", "10.0.0.5", null, null);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo(ApiTokenAuthenticator.AUTHENTICATION_CHALLENGE);
    }

    @Test
    void validBearerTokenCreatesABrowserSession() {
        MockServerWebExchange exchange =
                exchange("POST", "/bootui/api/auth/session", "10.0.0.5", "Bearer " + TOKEN, null);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange.getResponse().getCookies().getFirst("BOOTUI_SESSION"))
                .satisfies(cookie -> {
                    assertThat(cookie.isHttpOnly()).isTrue();
                    assertThat(cookie.getSameSite()).isEqualTo("Strict");
                });
    }

    @Test
    void sessionCookieAuthenticatesSubsequentRequests() {
        MockServerWebExchange exchange =
                exchange("GET", "/bootui/api/health", "10.0.0.5", null, "BOOTUI_SESSION=" + TOKEN);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static MockServerWebExchange exchange(
            String method, String path, String remoteAddress, String authorization, String cookie) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                        org.springframework.http.HttpMethod.valueOf(method), path)
                .remoteAddress(new InetSocketAddress(remoteAddress, 12345));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return MockServerWebExchange.from(builder.build());
    }
}
