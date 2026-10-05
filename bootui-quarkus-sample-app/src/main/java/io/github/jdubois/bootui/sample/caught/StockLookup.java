package io.github.jdubois.bootui.sample.caught;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import org.jboss.logging.Logger;

/**
 * A stock service that is always unavailable, and one handler per shape the caught-exceptions sensor reports
 * ({@code docs/PLAN-v2.md} M5-6), as on the Spring samples. Only {@link #stockOrDefault} is a finding: its
 * {@code IOException} is neither rethrown nor logged. Each counterexample logs it, logs its message, wraps and rethrows
 * it, or hands it on as a failed {@link Uni}.
 */
@ApplicationScoped
public class StockLookup {

    private static final Logger log = Logger.getLogger(StockLookup.class);

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
            log.warnf(ex, "Stock lookup failed for %s", sku);
            return 0;
        }
    }

    /** Logged with its message only, as {@code log.warn(e.getMessage())}. */
    public int stockLoggedMessage(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            log.warnf("Stock lookup failed: %s", ex.getMessage());
            return 0;
        }
    }

    /** Wrapped and rethrown: the resource's mapper answers a 404. */
    public int stockOrFail(String sku) {
        try {
            return read(sku);
        } catch (IOException ex) {
            throw new StockUnavailableException(sku, ex);
        }
    }

    /** Handed on as a failed {@link Uni}, which recovers with a default. */
    public Uni<Integer> stockLater(String sku) {
        Uni<Integer> stock;
        try {
            stock = Uni.createFrom().item(read(sku));
        } catch (IOException ex) {
            stock = Uni.createFrom().failure(ex);
        }
        return stock.onFailure().recoverWithItem(0);
    }

    private static int read(String sku) throws IOException {
        throw new IOException("The stock service is unavailable for " + sku);
    }
}
