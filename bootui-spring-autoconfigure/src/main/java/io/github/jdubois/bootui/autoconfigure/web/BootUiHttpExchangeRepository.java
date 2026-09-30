package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

/**
 * BootUI's fallback {@link HttpExchangeRepository}, registered only when the application defines none.
 *
 * <p>It replaces Actuator's {@code InMemoryHttpExchangeRepository} with the engine's failure-preserving
 * {@link TieredCaptureBuffer}, the same policy the Quarkus {@code HttpExchangeBuffer} uses: a bounded share of the
 * capacity is reserved for {@code 5xx} exchanges and exchanges at or above {@code
 * bootui.activity.request-slow-threshold-ms}, so a flood of successful requests cannot evict the most recent failures.
 * {@link #findAll()} stays newest-first, matching the reversed in-memory repository it replaces, so Actuator's
 * {@code httpexchanges} endpoint reads the same order. BootUI's own recording filter ({@link BootUiHttpExchangesFilter}
 * on the servlet stack, {@code BootUiHttpExchangesWebFilter} on WebFlux) keeps BootUI's own requests out of it while
 * {@code bootui.monitoring.exclude-self} is on.</p>
 *
 * <p>When the application records with its own {@code HttpExchangesFilter} or {@code HttpExchangesWebFilter}, BootUI
 * keeps its existing back-off. That is decided once, when the repository is created and before any request can be
 * recorded, from the registered bean definitions: the repository then behaves like the one it replaces (every
 * exchange kept, strictly oldest evicted first) and reports its retention as application-managed.</p>
 *
 * <p>Actuator calls {@link #add} on the request's own thread, after the rest of the chain, while
 * {@code RequestCorrelationFilter}'s correlation scope is still open, so the repository records the request id current
 * at that moment for each exchange ({@code docs/PLAN-v2.md} §5.1). {@code HttpExchange} is final and compared by
 * identity, so the ids are kept in a weak identity map that forgets an exchange once the buffer evicts it.</p>
 */
public final class BootUiHttpExchangeRepository implements HttpExchangeRepository {

    private final TieredCaptureBuffer<HttpExchange> buffer;
    private final Map<HttpExchange, String> requestIds = Collections.synchronizedMap(new WeakHashMap<>());
    private final long slowThresholdMillis;
    private final boolean recordedByApplication;

    /**
     * @param capacity {@code bootui.http-exchanges.max-exchanges}
     * @param reservedSharePercent {@code bootui.http-exchanges.reserved-share-percent}
     * @param slowThresholdMillis {@code bootui.activity.request-slow-threshold-ms}; {@code 0} disables slow
     *     classification
     * @param recordedByApplication whether an application-provided filter, not BootUI's, records into this repository
     */
    public BootUiHttpExchangeRepository(
            int capacity, int reservedSharePercent, long slowThresholdMillis, boolean recordedByApplication) {
        this.buffer = new TieredCaptureBuffer<>(capacity, recordedByApplication ? 0 : reservedSharePercent);
        this.slowThresholdMillis = Math.max(0L, slowThresholdMillis);
        this.recordedByApplication = recordedByApplication;
    }

    /**
     * Whether a recording filter other than BootUI's own is registered for this stack, so BootUI's repository is fed
     * by the application. Reads bean definitions only, without instantiating any bean.
     *
     * @param beanFactory the application context's bean factory
     * @param filterType the stack's recording filter type ({@code HttpExchangesFilter} or {@code HttpExchangesWebFilter})
     * @param bootUiFilterBeanName the name of BootUI's own recording filter bean
     */
    public static boolean isRecordedByApplication(
            ListableBeanFactory beanFactory, Class<?> filterType, String bootUiFilterBeanName) {
        for (String name : beanFactory.getBeanNamesForType(filterType, true, false)) {
            if (!bootUiFilterBeanName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<HttpExchange> findAll() {
        return buffer.newestFirst();
    }

    @Override
    public void add(HttpExchange exchange) {
        if (exchange == null) {
            return;
        }
        String requestId = BootUiCorrelation.current().requestId();
        if (requestId != null) {
            requestIds.put(exchange, requestId);
        }
        HttpExchange.Response response = exchange.getResponse();
        int status = response == null ? 0 : response.getStatus();
        Long durationMs =
                exchange.getTimeTaken() == null ? null : exchange.getTimeTaken().toMillis();
        buffer.add(
                exchange,
                !recordedByApplication && RequestSlowThreshold.isFailedOrSlow(status, durationMs, slowThresholdMillis));
    }

    /** The BootUI request id current when {@code exchange} was added, or {@code null}. */
    public String requestId(HttpExchange exchange) {
        return exchange == null ? null : requestIds.get(exchange);
    }

    /** Whether BootUI owns both this repository and the filter recording into it. */
    public boolean ownsRetention() {
        return !recordedByApplication;
    }

    /** The retained exchanges, newest first, with the counts that describe them, taken at one instant. */
    public TieredCaptureBuffer.Snapshot<HttpExchange> snapshot() {
        return buffer.snapshot();
    }

    /** The retention counts of a snapshot of this repository, or an application-managed marker. */
    public CaptureRetentionDto retention(TieredCaptureBuffer.Snapshot<HttpExchange> snapshot) {
        return ownsRetention()
                ? snapshot.retention(slowThresholdMillis)
                : CaptureRetentionDto.applicationManaged(snapshot.retained());
    }
}
