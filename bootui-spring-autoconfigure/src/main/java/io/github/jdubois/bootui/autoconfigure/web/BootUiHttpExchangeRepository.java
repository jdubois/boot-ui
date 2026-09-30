package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

/**
 * BootUI's fallback {@link HttpExchangeRepository}, registered only when the application defines none, and recorded
 * into by BootUI's own {@code HttpExchangesFilter} (servlet) or {@code HttpExchangesWebFilter} (reactive).
 *
 * <p>It replaces Actuator's {@code InMemoryHttpExchangeRepository} with the engine's failure-preserving
 * {@link TieredCaptureBuffer}, the same policy the Quarkus {@code HttpExchangeBuffer} uses: a bounded share of the
 * capacity is reserved for {@code 5xx} exchanges and exchanges at or above {@code
 * bootui.activity.request-slow-threshold-ms}, so a flood of successful requests cannot evict the most recent failures.
 * While {@code bootui.monitoring.exclude-self} is on, BootUI's own requests are dropped on {@link #add} rather than
 * hidden at read time, so console polling never displaces application exchanges, exactly as on Quarkus.
 * {@link #findAll()} stays newest-first, matching the reversed in-memory repository it replaces, so Actuator's
 * {@code httpexchanges} endpoint reads the same order.</p>
 *
 * <p>When the application contributes its own recording filter, BootUI keeps its existing back-off: the
 * configuration calls {@link #markRecordedByApplication()} before any request is served, after which this repository
 * behaves like the one it replaces (every exchange kept, strictly oldest evicted first) and reports its retention as
 * application-managed.</p>
 */
public final class BootUiHttpExchangeRepository implements HttpExchangeRepository {

    private final TieredCaptureBuffer<HttpExchange> buffer;
    private final long slowThresholdMillis;
    private final BootUiSelfDataFilter selfDataFilter;
    private volatile boolean recordedByApplication;

    /**
     * @param capacity {@code bootui.http-exchanges.max-exchanges}
     * @param reservedSharePercent {@code bootui.http-exchanges.reserved-share-percent}
     * @param slowThresholdMillis {@code bootui.activity.request-slow-threshold-ms}; {@code 0} disables slow
     *     classification
     * @param selfDataFilter decides whether an exchange is BootUI's own traffic and whether it is excluded
     */
    public BootUiHttpExchangeRepository(
            int capacity, int reservedSharePercent, long slowThresholdMillis, BootUiSelfDataFilter selfDataFilter) {
        this.buffer = new TieredCaptureBuffer<>(capacity, reservedSharePercent);
        this.slowThresholdMillis = Math.max(0L, slowThresholdMillis);
        this.selfDataFilter = selfDataFilter;
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
        if (recordedByApplication) {
            buffer.add(exchange, false);
            return;
        }
        if (isExcludedSelfExchange(exchange)) {
            return;
        }
        HttpExchange.Response response = exchange.getResponse();
        int status = response == null ? 0 : response.getStatus();
        Long durationMs =
                exchange.getTimeTaken() == null ? null : exchange.getTimeTaken().toMillis();
        buffer.add(exchange, RequestSlowThreshold.isFailedOrSlow(status, durationMs, slowThresholdMillis));
    }

    private boolean isExcludedSelfExchange(HttpExchange exchange) {
        HttpExchange.Request request = exchange.getRequest();
        if (request == null || request.getUri() == null || selfDataFilter == null) {
            return false;
        }
        return !selfDataFilter.shouldInclude(
                selfDataFilter.isBootUiPath(request.getUri().toString()));
    }

    /**
     * Records that an application-provided filter, not BootUI's, records into this repository. From then on the
     * repository applies no capture-time policy of its own, and its retention reads as application-managed.
     */
    public void markRecordedByApplication() {
        recordedByApplication = true;
    }

    /**
     * Marks BootUI's repository, when it is the context's repository, as recorded by the application whenever a
     * recording filter other than BootUI's own is registered.
     *
     * @param beanFactory the application context's bean factory, once every singleton exists
     * @param filterType the stack's recording filter type ({@code HttpExchangesFilter} or {@code HttpExchangesWebFilter})
     * @param bootUiFilterBeanName the name of BootUI's own recording filter bean
     */
    public static void detectApplicationRecording(
            ListableBeanFactory beanFactory, Class<?> filterType, String bootUiFilterBeanName) {
        for (String name : beanFactory.getBeanNamesForType(filterType, true, false)) {
            if (!bootUiFilterBeanName.equals(name)) {
                if (beanFactory.getBeanProvider(HttpExchangeRepository.class).getIfUnique()
                        instanceof BootUiHttpExchangeRepository repository) {
                    repository.markRecordedByApplication();
                }
                return;
            }
        }
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
