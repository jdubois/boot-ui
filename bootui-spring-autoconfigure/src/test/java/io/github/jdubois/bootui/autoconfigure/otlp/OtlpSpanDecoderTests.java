package io.github.jdubois.bootui.autoconfigure.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetryLimits;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtlpSpanDecoderTests {

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
    void decoderClampsConfiguredAttributeValueLength() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setMaxAttributeValueBytes(Integer.MAX_VALUE);
        OtlpSpanDecoder decoder = new OtlpSpanDecoder(properties.getTelemetry());
        String value = "x".repeat(TelemetryLimits.HARD_MAX_ATTRIBUTE_VALUE_CHARS + 128);

        List<NormalizedSpan> spans = decoder.decode(requestWithAttribute(value).toByteArray());

        String decoded = spans.get(0).attributes().get("large").asString();
        assertThat(decoded).hasSizeLessThan(value.length());
        assertThat(decoded).contains("truncated 128 chars");
    }
}
