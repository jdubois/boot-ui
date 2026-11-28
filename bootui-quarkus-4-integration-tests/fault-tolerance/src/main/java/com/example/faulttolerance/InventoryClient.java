package com.example.faulttolerance;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Declares one MicroProfile Fault Tolerance annotation of each kind the BootUI Fault Tolerance panel understands,
 * so the build-time Jandex scan has a representative inventory to capture. Nothing here performs a network
 * call, and no method is invoked by the tests: the panel is a declaration inventory, not a call driver.
 *
 * <p>{@code charge} carries a {@code @CircuitBreakerName}, the only case SmallRye can report live state for;
 * {@code settle} is anonymous, the case it cannot, which the panel reports as {@code UNKNOWN} rather than
 * guessing {@code CLOSED}. {@code exportReport}'s timeout is overridden through MicroProfile configuration in
 * {@code application.properties}, and {@code rebuildIndex} is disabled there, so both configuration paths are
 * proven end to end rather than only in unit tests.</p>
 */
@ApplicationScoped
public class InventoryClient {

    @Retry(maxRetries = 3, delay = 50)
    @Fallback(fallbackMethod = "reserveUnavailable")
    public String reserve(String sku) {
        return "reserved " + sku;
    }

    String reserveUnavailable(String sku) {
        return "unavailable " + sku;
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreakerName("inventory-service")
    public String charge(String orderId) {
        return "charged " + orderId;
    }

    @CircuitBreaker(requestVolumeThreshold = 4)
    public String settle(String orderId) {
        return "settled " + orderId;
    }

    /**
     * The one method here that fails, so {@code BootUiQuarkusFaultToleranceStateTransitionTest} can trip a named
     * breaker and prove BootUI captures the transition. It is deliberately a <em>separate</em> breaker from
     * {@code charge}: tripping it must not disturb the live-state assertions of the other tests, which share
     * this application instance.
     */
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0, delay = 10, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreakerName("payment-gateway")
    public String pay(String orderId) {
        throw new IllegalStateException("payment gateway unreachable for " + orderId);
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
