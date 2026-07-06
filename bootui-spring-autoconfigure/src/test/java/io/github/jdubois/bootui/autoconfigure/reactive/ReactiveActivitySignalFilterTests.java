package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link ReactiveActivitySignalFilter}: the WebFlux replacement for the servlet {@code
 * LiveActivityController}'s {@code @EventListener ServletRequestHandledEvent} method (see the filter's
 * Javadoc for why no reactive equivalent of that event exists).
 */
class ReactiveActivitySignalFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    private BootUiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
    }

    @Test
    void doesNotSignalForBootUiOwnTraffic() {
        ReactiveLiveActivityController controller = mock(ReactiveLiveActivityController.class);
        ReactiveActivitySignalFilter filter = new ReactiveActivitySignalFilter(properties, provider(controller));

        filter.filter(exchange("GET", "/bootui/api/activity"), OK_CHAIN).block(Duration.ofSeconds(5));
        filter.filter(exchange("GET", "/bootui/api/activity/stream"), OK_CHAIN).block(Duration.ofSeconds(5));
        filter.filter(exchange("GET", "/bootui"), OK_CHAIN).block(Duration.ofSeconds(5));

        verify(controller, never()).signalRequestHandled();
    }

    @Test
    void signalsTheControllerAfterApplicationTrafficCompletes() {
        ReactiveLiveActivityController controller = mock(ReactiveLiveActivityController.class);
        ReactiveActivitySignalFilter filter = new ReactiveActivitySignalFilter(properties, provider(controller));

        MockServerWebExchange exchange = exchange("GET", "/api/sample/products");
        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(controller).signalRequestHandled();
    }

    @Test
    void honorsCustomBasePathWhenExcludingBootUiTraffic() {
        properties.setPath("/app/console");
        properties.setApiPath("/app/console/api");
        ReactiveLiveActivityController controller = mock(ReactiveLiveActivityController.class);
        ReactiveActivitySignalFilter filter = new ReactiveActivitySignalFilter(properties, provider(controller));

        filter.filter(exchange("GET", "/app/console/api/activity"), OK_CHAIN).block(Duration.ofSeconds(5));

        verify(controller, never()).signalRequestHandled();
    }

    @Test
    void doesNotFailWhenControllerIsNotYetResolvable() {
        // The controller bean is lazily created (see BootUiReactiveAutoConfiguration); a request that
        // completes before it exists (e.g. nobody has opened the Live Activity panel yet) must not fail.
        ReactiveActivitySignalFilter filter = new ReactiveActivitySignalFilter(properties, empty());

        MockServerWebExchange exchange = exchange("GET", "/api/sample/products");

        assertThat(filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5)))
                .isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static MockServerWebExchange exchange(String method, String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), uri));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveLiveActivityController> provider(ReactiveLiveActivityController value) {
        ObjectProvider<ReactiveLiveActivityController> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveLiveActivityController> empty() {
        ObjectProvider<ReactiveLiveActivityController> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
