package io.github.jdubois.bootui.autoconfigure.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryLimitsTests {

    private static NormalizedSpan span(String traceId, String spanId) {
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "GET /sample",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of(),
                List.of());
    }

    private static ExportTraceServiceRequest requestWithAttribute(String value) {
        Span span = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(hexToBytes("0123456789abcdef0123456789abcdef")))
                .setSpanId(ByteString.copyFrom(hexToBytes("1111111111111111")))
                .setName("large span")
                .setStartTimeUnixNano(1L)
                .setEndTimeUnixNano(2L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("large")
                        .setValue(AnyValue.newBuilder().setStringValue(value).build())
                        .build())
                .build();
        ResourceSpans resourceSpans = ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().build())
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(span).build())
                .build();
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(resourceSpans)
                .build();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void storeClampsConfiguredTraceCapacity() {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setMaxTraces(0);
        TelemetryStore store = new TelemetryStore(properties.getTelemetry());

        store.add(span("trace-a", "span-a"));
        store.add(span("trace-b", "span-b"));

        assertThat(store.capacity()).isEqualTo(1);
        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.findTrace("trace-a")).isNull();
        assertThat(store.findTrace("trace-b")).isNotNull();
    }

    @Test
    void storeClampsConfiguredSpanCapacity() {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setMaxSpansPerTrace(Integer.MAX_VALUE);
        TelemetryStore store = new TelemetryStore(properties.getTelemetry());

        for (int i = 0; i < TelemetryStore.HARD_MAX_SPANS_PER_TRACE + 5; i++) {
            store.add(span("trace-a", "span-" + i));
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(TelemetryStore.HARD_MAX_SPANS_PER_TRACE);
    }

    @Test
    void decoderClampsConfiguredAttributeValueLength() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setMaxAttributeValueBytes(Integer.MAX_VALUE);
        OtlpSpanDecoder decoder = new OtlpSpanDecoder(properties.getTelemetry());
        String value = "x".repeat(OtlpSpanDecoder.HARD_MAX_ATTRIBUTE_VALUE_CHARS + 128);

        List<NormalizedSpan> spans = decoder.decode(requestWithAttribute(value).toByteArray());

        String decoded = spans.get(0).attributes().get("large").asString();
        assertThat(decoded).hasSizeLessThan(value.length());
        assertThat(decoded).contains("truncated 128 chars");
    }

    @Test
    void suspendForIdleClearsAndStopsIngestionUntilResumed() {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(properties.getTelemetry());
        assertThat(store.add(span("trace-a", "span-a"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);

        store.suspendForIdle();
        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.add(span("trace-b", "span-b"), false)).isFalse();
        assertThat(store.retainedTraceCount()).isZero();

        store.resumeFromIdle();
        assertThat(store.add(span("trace-c", "span-c"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);
    }
}
