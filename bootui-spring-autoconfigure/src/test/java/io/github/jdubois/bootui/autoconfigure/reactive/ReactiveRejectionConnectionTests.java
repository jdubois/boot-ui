package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.netty.handler.codec.http.HttpVersion;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.OverridingClassLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;

class ReactiveRejectionConnectionTests {

    @Test
    void queuedReadOnlyRejectionClosesTheStalledConnectionAndNextCachedReadCompletes() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        AtomicInteger accepted = new AtomicInteger();
        var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                    int request = accepted.incrementAndGet();
                    byte[] body = "cached".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponse().getHeaders().setContentLength(body.length);
                    Mono<Void> write = exchange.getResponse()
                            .writeWith(Mono.just(
                                    exchange.getResponse().bufferFactory().wrap(body)));
                    return request == 1 ? Mono.delay(Duration.ofMillis(50)).then(write) : write;
                })
                .filter(new ReactivePanelAccessFilter(properties))
                .build();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(handler))
                .bindNow();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10000);
            var input = new BufferedInputStream(socket.getInputStream());
            socket.getOutputStream()
                    .write(("GET /bootui/api/mysql HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                    + "POST /bootui/api/mysql/read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            String acceptedResponse = readResponse(input);
            assertThat(acceptedResponse).startsWith("HTTP/1.1 200").contains("cached");
            assertThat(acceptedResponse.toLowerCase(Locale.ROOT)).doesNotContain("connection: close");
            assertThat(readResponse(input))
                    .startsWith("HTTP/1.1 403")
                    .containsIgnoringCase("connection: close")
                    .contains("bootui.read-only=true");
            assertThat(input.read())
                    .as("rejected keep-alive connection closes instead of stalling")
                    .isEqualTo(-1);
            try (Socket next = new Socket("127.0.0.1", server.port())) {
                next.setSoTimeout(10000);
                next.getOutputStream()
                        .write("GET /bootui/api/mysql HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                assertThat(readResponse(next.getInputStream())).startsWith("HTTP/1.1 200");
            }
            assertThat(accepted).hasValue(2);
        } finally {
            server.disposeNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.0", "HTTP/1.1", "HTTP/2.0"})
    void closesOnlyHttp1RejectionsIncludingDecoratedResponses(String version) {
        HttpServerResponse nativeResponse = mock(HttpServerResponse.class);
        when(nativeResponse.version()).thenReturn(HttpVersion.valueOf(version));
        AbstractServerHttpResponse response = mock(AbstractServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getNativeResponse()).thenReturn(nativeResponse);
        when(response.getHeaders()).thenReturn(headers);

        ReactorNettyRejectionPolicy.closeHttp1Connection(
                new ServerHttpResponseDecorator(new ServerHttpResponseDecorator(response)));

        assertThat(headers.getFirst(HttpHeaders.CONNECTION)).isEqualTo(version.startsWith("HTTP/1.") ? "close" : null);
    }

    @Test
    void otherWebFluxServersAreUnaffected() {
        AbstractServerHttpResponse response = mock(AbstractServerHttpResponse.class);
        when(response.getNativeResponse()).thenReturn(new Object());
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);
        ReactorNettyRejectionPolicy.closeHttp1Connection(response);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONNECTION)).isFalse();
    }

    @Test
    void responsesWithoutNativeTransportAreUnaffected() {
        MockServerHttpResponse response = new MockServerHttpResponse();
        ReactorNettyRejectionPolicy.closeHttp1Connection(response);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONNECTION)).isFalse();
    }

    @Test
    void http2RejectionsKeepTheirStreamProtocolWithoutAConnectionHeader() {
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        var handler = WebHttpHandlerBuilder.webHandler(exchange -> Mono.error(new AssertionError("must reject")))
                .filter(new ReactivePanelAccessFilter(properties))
                .build();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .protocol(HttpProtocol.H2C)
                .handle(new ReactorHttpHandlerAdapter(handler))
                .bindNow();
        try {
            String body = HttpClient.create()
                    .protocol(HttpProtocol.H2C)
                    .host("127.0.0.1")
                    .port(server.port())
                    .post()
                    .uri("/bootui/api/mysql/read")
                    .responseSingle((response, content) -> {
                        assertThat(response.status().code()).isEqualTo(403);
                        assertThat(response.responseHeaders().contains(HttpHeaders.CONNECTION))
                                .isFalse();
                        return content.asString();
                    })
                    .block(Duration.ofSeconds(10));
            assertThat(body).contains("bootui.read-only=true");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void filtersCanRejectRequestsWhenReactorNettyIsAbsent() throws Exception {
        ClassLoader loader = new OverridingClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("reactor.netty.") || name.startsWith("io.netty.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }

            @Override
            protected boolean isEligibleForOverriding(String name) {
                return name.startsWith("io.github.jdubois.bootui.autoconfigure.reactive.");
            }
        };
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        WebFilter filter = (WebFilter) loader.loadClass(ReactivePanelAccessFilter.class.getName())
                .getConstructor(BootUiProperties.class)
                .newInstance(properties);
        assertThat(filter.getClass().getClassLoader()).isSameAs(loader);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/bootui/api/mysql/read"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must reject")))
                .block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.CONNECTION))
                .isFalse();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("bootui.read-only=true");
    }

    private static String readResponse(InputStream input) throws Exception {
        StringBuilder headers = new StringBuilder();
        while (!headers.toString().endsWith("\r\n\r\n")) {
            int value = input.read();
            assertThat(value).as("complete HTTP response headers").isNotNegative();
            assertThat(headers.length()).isLessThan(8192);
            headers.append((char) value);
        }
        String head = headers.toString();
        int length = -1;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        assertThat(length).as("bounded response body").isBetween(0, 4096);
        byte[] body = input.readNBytes(length);
        assertThat(body).hasSize(length);
        return head + new String(body, StandardCharsets.UTF_8);
    }
}
