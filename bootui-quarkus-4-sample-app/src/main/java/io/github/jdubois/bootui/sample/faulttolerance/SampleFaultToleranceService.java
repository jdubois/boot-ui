package io.github.jdubois.bootui.sample.faulttolerance;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

/**
 * Declares one SmallRye Fault Tolerance annotation of each kind the BootUI Fault Tolerance panel understands, so
 * the Quarkus sample application shows a representative inventory.
 *
 * <p>The named circuit breaker is the case SmallRye can report live state for; the anonymous one is the case
 * it cannot, which the panel shows as {@code UNKNOWN} rather than guessing {@code CLOSED}. Every failure is
 * simulated locally — nothing here performs a network call.</p>
 */
@ApplicationScoped
public class SampleFaultToleranceService {

    private static final Logger LOG = Logger.getLogger(SampleFaultToleranceService.class);

    private final AtomicInteger reservations = new AtomicInteger();

    @Retry(maxRetries = 3, delay = 50)
    @Fallback(fallbackMethod = "reserveUnavailable")
    public String reserve(String sku) {
        if (reservations.incrementAndGet() % 2 == 1) {
            throw new IllegalStateException("inventory service temporarily unavailable");
        }
        return "reserved " + sku;
    }

    String reserveUnavailable(String sku) {
        LOG.info("giving up on reserving " + sku);
        return "unavailable " + sku;
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreakerName("inventory-service")
    public String charge(String orderId) {
        throw new IllegalStateException("payment gateway unreachable for " + orderId);
    }

    @CircuitBreaker(requestVolumeThreshold = 4)
    public String settle(String orderId) {
        return "settled " + orderId;
    }

    @Timeout(2000)
    public String exportReport() {
        return "exported";
    }

    @Bulkhead(value = 4, waitingTaskQueue = 8)
    public String rebuildIndex() {
        return "rebuilt";
    }

    @RateLimit(value = 20)
    public String search(String term) {
        return "searched " + term;
    }
}
