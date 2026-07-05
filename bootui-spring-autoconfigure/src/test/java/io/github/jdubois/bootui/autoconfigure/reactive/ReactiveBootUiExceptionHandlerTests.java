package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

/**
 * Tests for {@link ReactiveBootUiExceptionHandler}: proves it records into the shared
 * {@link ExceptionStore} and then always re-propagates the original exception unchanged, so the
 * application's own error handling still renders the response - mirroring the
 * "observe, never swallow" contract {@code BootUiExceptionHandlerResolver} has on the servlet side.
 */
class ReactiveBootUiExceptionHandlerTests {

    @Test
    void recordsTheExceptionAndRePropagatesItUnchanged() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        ReactiveBootUiExceptionHandler handler = new ReactiveBootUiExceptionHandler(store);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/bootui/api/beans"));
        RuntimeException thrown = new RuntimeException("boom");

        StepVerifier.create(handler.handle(exchange, thrown))
                .expectErrorMatches(ex -> ex == thrown)
                .verify();

        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(store.groups()).hasSize(1);
    }

    @Test
    void capturesTheRequestMethodAndPath() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        ReactiveBootUiExceptionHandler handler = new ReactiveBootUiExceptionHandler(store);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, "/bootui/api/config"));

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("nope")))
                .expectError(IllegalStateException.class)
                .verify();

        ExceptionStore.GroupDetail detail = store.find(store.groups().get(0).fingerprint());
        ExceptionStore.Occurrence occurrence =
                detail.occurrences().get(detail.occurrences().size() - 1);
        assertThat(occurrence.requestMethod()).isEqualTo("POST");
        assertThat(occurrence.requestPath()).isEqualTo("/bootui/api/config");
    }

    @Test
    void runsAtHighestPrecedenceSoItObservesExceptionsBeforeBootsDefaultHandler() {
        ReactiveBootUiExceptionHandler handler = new ReactiveBootUiExceptionHandler(new ExceptionStore(1, 1, 1));
        assertThat(handler.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
