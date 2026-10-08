package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.activity.FakeRequestValues;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.RequestValuesBinding;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring WebFlux's push point of the request value holder ({@code docs/PLAN-v2.md} §5.16, M5-6b): the query parameters
 * WebFlux parsed from the URI, the exchange's attributes for the path variables a handler mapping sets later, never
 * the form data, and the values removed when the request's chain ends, however it ends.
 */
class ReactiveRequestValuesTests {

    private final ReactiveRequestCorrelationFilter filter =
            new ReactiveRequestCorrelationFilter(new BootUiProperties());

    @BeforeEach
    void bind() {
        FakeRequestValues.reset();
        RequestValuesBinding.bind(FakeRequestValues.class);
        AgentRequestValues.configure(true);
    }

    @AfterEach
    void unbind() {
        AgentRequestValues.configure(false);
        RequestValuesBinding.rebind();
        FakeRequestValues.reset();
    }

    @Test
    void queryValuesAndTheLatePathVariablesArePushedAndEndWithTheChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, URI.create("/api/files/q3?name=Robert%27%29&tag=a+b"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("secret=form-value"));
        AtomicReference<String> requestId = new AtomicReference<>();
        WebFilterChain chain = served -> {
            requestId.set(BootUiCorrelation.current().requestId());
            assertThat(FakeRequestValues.CALLS).containsExactly("begin " + requestId.get());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(5));

        assertThat(FakeRequestValues.PUSHED)
                .containsEntry("name", "Robert')")
                .containsEntry("tag", "a b")
                .doesNotContainKey("secret");
        assertThat(FakeRequestValues.late).isSameAs(exchange.getAttributes());
        assertThat(FakeRequestValues.lateKeys).contains(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + requestId.get(), "end " + requestId.get());
        assertThat(exchange.getFormData().block(Duration.ofSeconds(5)).getFirst("secret"))
                .as("the form data was never consumed")
                .isEqualTo("form-value");
    }

    @Test
    void aFailedOrCancelledChainEndsItsValuesToo() {
        filter.filter(exchange(), served -> Mono.error(new IllegalStateException("boom")))
                .onErrorResume(ex -> Mono.empty())
                .block(Duration.ofSeconds(5));
        filter.filter(exchange(), served -> Mono.never()).subscribe().dispose();

        assertThat(FakeRequestValues.CALLS.stream().filter(call -> call.startsWith("end ")))
                .hasSize(2);
    }

    @Test
    void nothingIsPushedWhileMatchingIsOff() {
        AgentRequestValues.configure(false);
        filter.filter(exchange(), served -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(FakeRequestValues.CALLS).isEmpty();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/search?name=alice"));
    }
}
