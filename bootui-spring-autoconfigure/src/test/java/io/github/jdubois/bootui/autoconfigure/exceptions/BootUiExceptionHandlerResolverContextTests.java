package io.github.jdubois.bootui.autoconfigure.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BootUiExceptionHandlerResolverContextTests {

    @Test
    void correlatesAtExistingResolverWithoutHandlingOrDuplicatingAnOccurrence() {
        var store = new ExceptionStore(100, 25, 50);
        var resolver = new BootUiExceptionHandlerResolver(store);
        resolver.setInvocationContextProvider(
                () -> new InvocationContextProvider.Context("request-trace", "host-span-after-unwind"));
        var request = new MockHttpServletRequest("GET", "/orders");
        request.setQueryString("secret=value");
        var failure = new IllegalArgumentException("failure");
        assertThat(resolver.resolveException(request, new MockHttpServletResponse(), null, failure))
                .isNull();
        resolver.resolveException(request, new MockHttpServletResponse(), null, failure);
        assertThat(store.groups())
                .singleElement()
                .satisfies(group -> assertThat(store.find(group.fingerprint()).occurrences())
                        .singleElement()
                        .satisfies(occurrence -> {
                            assertThat(occurrence.traceId()).isEqualTo("request-trace");
                            assertThat(occurrence.requestPath()).isEqualTo("/orders");
                        }));
    }
}
