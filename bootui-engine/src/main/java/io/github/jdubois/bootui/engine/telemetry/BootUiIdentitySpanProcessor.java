package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * OpenTelemetry {@link SpanProcessor} that stamps BootUI <em>identity</em> attributes on every span at
 * start ({@link BootUiSpanAttributes#ENRICHED}, {@link BootUiSpanAttributes#SERVICE service},
 * {@link BootUiSpanAttributes#INSTANCE instance}). Identity is written in {@code onStart} because a span
 * is read-only by {@code onEnd}; per-request depth (SQL / exceptions) is added separately at capture time
 * through {@link OtelSpanEnricher}.
 *
 * <p>One of the few engine types that touches the OpenTelemetry SDK (optional dependency, pinned by a
 * concentration ArchUnit rule). Each adapter registers it as a span processor, gated identically to the
 * BootUI span exporter, and only when telemetry enrichment is enabled.</p>
 */
public final class BootUiIdentitySpanProcessor implements SpanProcessor {

    private final TelemetrySettings settings;

    private final String serviceName;

    private final String instanceId;

    public BootUiIdentitySpanProcessor(TelemetrySettings settings, String serviceName, String instanceId) {
        this.settings = settings;
        this.serviceName = BlankStrings.blankToNull(serviceName);
        this.instanceId = BlankStrings.blankToNull(instanceId);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (!settings.enabled() || !settings.enrichmentEnabled()) {
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
