package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusOtelTraceIdProvider}'s span-reading logic — the seam that lets the Live
 * Activity timeline correlate signals on Quarkus. Uses only the OpenTelemetry API (no SDK): a span wrapped
 * around a synthetic {@link SpanContext} is made current, exactly as Quarkus' real server span would be in
 * the request context. The end-to-end propagation across the event-loop→worker hop is proven by the
 * {@code otel} integration-test module; here we pin that an active span yields its trace id and an absent
 * one yields {@code null}.
 */
class QuarkusOtelTraceIdProviderTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    @Test
    void returnsTraceIdOfActiveSpan() {
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            assertThat(QuarkusOtelTraceIdProvider.currentSpanTraceId()).isEqualTo(TRACE_ID);
        }
    }

    @Test
    void returnsNullWhenNoSpanIsActive() {
        assertThat(QuarkusOtelTraceIdProvider.currentSpanTraceId()).isNull();
    }

    @Test
    void returnsNullForAnInvalidSpanContext() {
        try (Scope ignored = Span.wrap(SpanContext.getInvalid()).makeCurrent()) {
            assertThat(QuarkusOtelTraceIdProvider.currentSpanTraceId()).isNull();
        }
    }

    @Test
    void producedProviderReadsTheActiveSpan() {
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        var provider = new QuarkusOtelTraceIdProvider().bootUiOtelTraceIdProvider();
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            assertThat(provider.currentTraceId()).isEqualTo(TRACE_ID);
        }
        assertThat(provider.currentTraceId()).isNull();
    }
}
