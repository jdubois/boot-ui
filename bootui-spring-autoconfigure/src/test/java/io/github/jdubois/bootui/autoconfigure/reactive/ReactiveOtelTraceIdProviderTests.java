package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReactiveOtelTraceIdProvider}'s span-reading logic - the seam that lets the Live
 * Activity timeline correlate SQL/exceptions/security events/HTTP exchanges on WebFlux. Mirrors {@code
 * QuarkusOtelTraceIdProviderTest} exactly: uses only the OpenTelemetry API (no SDK), making a span wrapped
 * around a synthetic {@link SpanContext} current, exactly as Spring Boot's own reactive OpenTelemetry
 * instrumentation would in a real request context.
 */
class ReactiveOtelTraceIdProviderTests {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    @Test
    void returnsTraceIdOfActiveSpan() {
        ReactiveOtelTraceIdProvider provider = new ReactiveOtelTraceIdProvider();
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            assertThat(provider.currentTraceId()).isEqualTo(TRACE_ID);
        }
    }

    @Test
    void returnsNullWhenNoSpanIsActive() {
        assertThat(new ReactiveOtelTraceIdProvider().currentTraceId()).isNull();
    }

    @Test
    void returnsNullForAnInvalidSpanContext() {
        ReactiveOtelTraceIdProvider provider = new ReactiveOtelTraceIdProvider();

        try (Scope ignored = Span.wrap(SpanContext.getInvalid()).makeCurrent()) {
            assertThat(provider.currentTraceId()).isNull();
        }
    }
}
