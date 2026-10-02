package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * OpenTelemetry {@link SpanProcessor} that observes every span at start, on the thread that starts it. It tells the
 * {@link TelemetryStore} which BootUI request or execution started the span ({@link TelemetryStore#spanStarted}), so
 * an AI call exported later on the exporter's thread nests under that request by id ({@code docs/PLAN-v2.md} §5.3).
 * When telemetry enrichment is enabled it also stamps BootUI <em>identity</em> attributes
 * ({@link BootUiSpanAttributes#ENRICHED}, {@link BootUiSpanAttributes#SERVICE service},
 * {@link BootUiSpanAttributes#INSTANCE instance}). Both happen in {@code onStart}, because the starting thread's
 * correlation is gone, and a span is read-only, by {@code onEnd}; per-request depth (SQL / exceptions) is added
 * separately at capture time through {@link OtelSpanEnricher}.
 *
 * <p>One of the few engine types that touches the OpenTelemetry SDK (optional dependency, pinned by a
 * concentration ArchUnit rule). Each adapter registers it as a span processor, gated identically to the
 * BootUI span exporter; it re-reads the live capture and enrichment toggles on every span.</p>
 */
public final class BootUiIdentitySpanProcessor implements SpanProcessor {

    private final TelemetrySettings settings;

    private final String serviceName;

    private final String instanceId;

    private final TelemetryStore store;

    /** A processor that only stamps identity attributes. */
    public BootUiIdentitySpanProcessor(TelemetrySettings settings, String serviceName, String instanceId) {
        this(settings, serviceName, instanceId, null);
    }

    /**
     * A processor that also tells {@code store} which request or execution starts each span.
     *
     * @param store the store the BootUI span exporter feeds, or {@code null}
     */
    public BootUiIdentitySpanProcessor(
            TelemetrySettings settings, String serviceName, String instanceId, TelemetryStore store) {
        this.settings = settings;
        this.serviceName = BlankStrings.blankToNull(serviceName);
        this.instanceId = BlankStrings.blankToNull(instanceId);
        this.store = store;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (!settings.enabled()) {
            return;
        }
        if (store != null) {
            try {
                store.spanStarted(span.getSpanContext().getSpanId());
            } catch (RuntimeException ignored) {
                // Correlation must never disrupt span creation.
            }
        }
        if (!settings.enrichmentEnabled()) {
            return;
        }
        try {
            span.setAttribute(BootUiSpanAttributes.ENRICHED, true);
            if (serviceName != null) {
                span.setAttribute(BootUiSpanAttributes.SERVICE, serviceName);
            }
            if (instanceId != null) {
                span.setAttribute(BootUiSpanAttributes.INSTANCE, instanceId);
            }
        } catch (RuntimeException ignored) {
            // Enrichment must never disrupt span creation.
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Identity is stamped at start; nothing to do on end.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
