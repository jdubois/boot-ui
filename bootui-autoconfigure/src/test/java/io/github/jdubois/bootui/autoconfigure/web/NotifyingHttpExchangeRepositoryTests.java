package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

class NotifyingHttpExchangeRepositoryTests {

    private static HttpExchange exchange(String path) {
        return new HttpExchange(
                Instant.parse("2026-06-03T09:15:00Z"),
                new HttpExchange.Request(URI.create("http://localhost" + path), "127.0.0.1", "GET", Map.of()),
                new HttpExchange.Response(200, Map.of()),
                null,
                null,
                Duration.ofMillis(5));
    }

    @Test
    void addForwardsToDelegateThenSignals() {
        HttpExchangeRepository delegate = mock(HttpExchangeRepository.class);
        BootUiChangeStream changeStream = mock(BootUiChangeStream.class);
        NotifyingHttpExchangeRepository repository = new NotifyingHttpExchangeRepository(delegate, changeStream);

        HttpExchange recorded = exchange("/api/orders");
        repository.add(recorded);

        verify(delegate).add(recorded);
        verify(changeStream).signal();
    }

    @Test
    void findAllForwardsToDelegateWithoutSignalling() {
        HttpExchangeRepository delegate = mock(HttpExchangeRepository.class);
        BootUiChangeStream changeStream = mock(BootUiChangeStream.class);
        List<HttpExchange> exchanges = List.of(exchange("/one"));
        when(delegate.findAll()).thenReturn(exchanges);
        NotifyingHttpExchangeRepository repository = new NotifyingHttpExchangeRepository(delegate, changeStream);

        assertThat(repository.findAll()).isSameAs(exchanges);
        verify(changeStream, never()).signal();
    }

    @Test
    void exposesWrappedDelegate() {
        HttpExchangeRepository delegate = mock(HttpExchangeRepository.class);
        NotifyingHttpExchangeRepository repository =
                new NotifyingHttpExchangeRepository(delegate, mock(BootUiChangeStream.class));

        assertThat(repository.delegate()).isSameAs(delegate);
    }
}
