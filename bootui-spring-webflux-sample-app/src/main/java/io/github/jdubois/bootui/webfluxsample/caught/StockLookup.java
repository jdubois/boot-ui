package io.github.jdubois.bootui.webfluxsample.caught;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A stock service that is always unavailable, and one handler per shape the caught-exceptions sensor reports
 * ({@code docs/PLAN-v2.md} M5-6), as on the Spring MVC sample. Only {@link #stockOrDefault} is a finding: its
 * {@code IOException} is neither rethrown nor logged. Each counterexample logs it, logs its message, wraps and rethrows
 * it, or hands it on as an error signal.
 */
@Component
public class StockLookup {

    private static final Logger log = LoggerFactory.getLogger(StockLookup.class);

    /** The seed: the failure is dropped and a default returned. */
    public int stockOrDefault(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            return 0;
        }
    }

    /** Logged with the exception. */
    public int stockLogged(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            log.warn("Stock lookup failed for {}", sku, ex);
            return 0;
        }
    }

    /** Logged with its message only, as {@code log.warn(e.getMessage())}. */
    public int stockLoggedMessage(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            log.warn("Stock lookup failed: {}", ex.getMessage());
            return 0;
        }
    }

    /** Wrapped and rethrown: the controller's exception maps it to a 404. */
    public int stockOrFail(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            throw new StockUnavailableException(sku, ex);
        }
    }

    /** Handed on as an error signal, which recovers with a default. */
    public Mono<Integer> stockLater(String sku) {
        Mono<Integer> stock;
        try {
            stock = Mono.just(read(sku));
        } catch (IOException ex) {
            stock = Mono.error(ex);
        }
        return stock.onErrorReturn(0);
    }

    private static int read(String sku) throws IOException {
        throw new IOException("The stock service is unavailable for " + sku);
    }
}
