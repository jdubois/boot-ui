package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code BootUiGuardPathMatchingTests}: the guards must match the decoded
 * path {@code PathPattern} routes on, so {@code /%62ootui/api/**} and {@code /bootui;x=1/api/**} cannot
 * disarm them while still reaching the BootUI handler.
 */
class ReactiveBootUiGuardPathMatchingTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void localhostGuardStillRejectsNonLoopbackSources(String uri) {
        MockServerWebExchange exchange = exchange(uri, "10.0.0.5");

        new ReactiveLocalhostOnlyFilter(new BootUiProperties())
                .filter(exchange, OK_CHAIN)
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void bearerTokenIsStillRequiredForNonLoopbackSources(String uri) {
        BootUiProperties properties = new BootUiProperties();
        ReactiveApiAuthenticationFilter filter = new ReactiveApiAuthenticationFilter(
                properties, new ApiTokenAuthenticator("test-token"), new ReactiveLocalhostOnlyFilter(properties));
        MockServerWebExchange exchange = exchange(uri, "10.0.0.5");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void panelPolicyStillAppliesToDisabledPanels(String uri) {
        BootUiProperties properties = new BootUiProperties();
        properties.panel("config").setEnabled(false);
        MockServerWebExchange exchange = exchange(uri, "127.0.0.1");

        new ReactivePanelAccessFilter(properties).filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void canonicalPathsAreStillMatched() {
        BootUiProperties properties = new BootUiProperties();
        properties.panel("config").setEnabled(false);
        MockServerWebExchange exchange = exchange("/bootui/api/config", "127.0.0.1");

        new ReactivePanelAccessFilter(properties).filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonBootUiRequestsAreStillLeftAlone() {
        MockServerWebExchange exchange = exchange("/api/orders", "10.0.0.5");

        new ReactiveLocalhostOnlyFilter(new BootUiProperties())
                .filter(exchange, OK_CHAIN)
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static MockServerWebExchange exchange(String uri, String remoteAddress) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(uri))
                .remoteAddress(new InetSocketAddress(remoteAddress, 12345))
                .build());
    }
}
