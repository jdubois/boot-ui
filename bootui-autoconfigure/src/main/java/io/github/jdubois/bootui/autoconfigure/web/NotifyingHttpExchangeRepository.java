package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import java.util.List;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

/**
 * Transparent {@link HttpExchangeRepository} decorator that taps the existing recording pipeline so
 * the HTTP Exchanges panel can update live over Server-Sent Events instead of polling.
 *
 * <p>Every completed request is pushed into the repository through {@link #add(HttpExchange)} by the
 * Spring Boot {@code HttpExchangesFilter}. By wrapping that {@code add(...)} call we get a natural
 * change event without introducing a second servlet filter: the wrapper simply forwards to the
 * delegate and then fires a coalesced {@link BootUiChangeStream#signal()} tick. This mirrors how the
 * SQL Trace panel turns {@code SqlTraceRecorder} changes into the same SSE notification.</p>
 *
 * <p>{@link #signal()} is intentionally off the hot path: {@code BootUiChangeStream} only flips a
 * dirty flag and schedules a single delayed flush, so a burst of requests produces one push and the
 * recording thread never blocks on SSE I/O.</p>
 */
public final class NotifyingHttpExchangeRepository implements HttpExchangeRepository {

    private final HttpExchangeRepository delegate;

    private final BootUiChangeStream changeStream;

    public NotifyingHttpExchangeRepository(HttpExchangeRepository delegate, BootUiChangeStream changeStream) {
        this.delegate = delegate;
        this.changeStream = changeStream;
    }

    @Override
    public List<HttpExchange> findAll() {
        return delegate.findAll();
    }

    @Override
    public void add(HttpExchange httpExchange) {
        delegate.add(httpExchange);
        changeStream.signal();
    }

    /** Returns the wrapped repository; used to avoid double-wrapping. */
    public HttpExchangeRepository delegate() {
        return delegate;
    }
}
