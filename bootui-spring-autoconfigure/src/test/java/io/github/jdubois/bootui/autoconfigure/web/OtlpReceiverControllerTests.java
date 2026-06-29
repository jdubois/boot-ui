package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.google.protobuf.ByteString;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link OtlpReceiverController}.
 *
 * <p>Posts protobuf-encoded {@code ExportTraceServiceRequest} payloads to the
 * receiver and asserts the OTLP contract: a 200 with an empty protobuf body on
 * success, the self-span exclusion governed by {@code bootui.telemetry
 * .exclude-self-spans}, and the disabled / empty / oversize / invalid branches.
 * The receiver and decoder read {@link BootUiProperties} live, so each test can
 * flip a flag after the controller is built.</p>
 */
class OtlpReceiverControllerTests {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    private static final String HOST_TRACE_ID = "fedcba9876543210fedcba9876543210";

    private static final String HOST_SPAN_ID = "1111111111111111";

    private static final String SELF_SPAN_ID = "2222222222222222";

    private static final String SELF_CHILD_SPAN_ID = "3333333333333333";

    private BootUiProperties properties;

    private TelemetryStore store;

    private MockMvc mvc;

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build();
    }

    private static ByteString bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return ByteString.copyFrom(out);
    }

    private static ExportTraceServiceRequest request(Span... spans) {
        ScopeSpans.Builder scope = ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder()
                        .setName("io.micrometer.observation")
                        .build());
        for (Span span : spans) {
            scope.addSpans(span);
        }
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("service.name", "sample"))
                                .build())
                        .addScopeSpans(scope.build())
                        .build())
                .build();
    }

    private static Span hostSpan() {
        long now = System.currentTimeMillis() * 1_000_000L;
        return Span.newBuilder()
                .setTraceId(bytes(HOST_TRACE_ID))
                .setSpanId(bytes(HOST_SPAN_ID))
                .setName("chat qwen3")
                .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(now)
                .setEndTimeUnixNano(now + 1_000_000L)
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_OK)
                        .build())
                .addAttributes(stringAttr("gen_ai.operation.name", "chat"))
                .addAttributes(stringAttr("gen_ai.system", "ollama"))
                .build();
    }

    private static Span selfSpan() {
        long now = System.currentTimeMillis() * 1_000_000L;
        return Span.newBuilder()
                .setTraceId(bytes(TRACE_ID))
                .setSpanId(bytes(SELF_SPAN_ID))
                .setName("http get")
                .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                .setStartTimeUnixNano(now)
                .setEndTimeUnixNano(now + 1_000_000L)
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_OK)
                        .build())
                .addAttributes(stringAttr("http.route", "/bootui/api/overview"))
                .build();
    }

    /**
     * A nested Spring Security observation span that belongs to the same BootUI request as
     * {@link #selfSpan()} but carries no path attribute, so it can only be recognized as BootUI's
     * own through its trace association.
     */
    private static Span selfFilterChainSpan() {
        long now = System.currentTimeMillis() * 1_000_000L;
        return Span.newBuilder()
                .setTraceId(bytes(TRACE_ID))
                .setSpanId(bytes(SELF_CHILD_SPAN_ID))
                .setParentSpanId(bytes(SELF_SPAN_ID))
                .setName("security filterchain before")
                .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                .setStartTimeUnixNano(now)
                .setEndTimeUnixNano(now + 1_000_000L)
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_OK)
                        .build())
                .build();
    }

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        properties.getTelemetry().setEnabled(true);
        store = new TelemetryStore(new SpringTelemetrySettings(properties));
        OtlpSpanDecoder decoder = new OtlpSpanDecoder(properties.getTelemetry());
        mvc = standaloneSetup(new OtlpReceiverController(store, decoder, properties))
                .build();
    }

    @Test
    void storesSpansFromValidPayloadAndReturnsEmptyProtobuf() throws Exception {
        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(hostSpan()).toByteArray()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-protobuf"))
                .andExpect(content().bytes(new byte[0]));

        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.allSpansSnapshot()).hasSize(1);
    }

    @Test
    void rejectsPayloadWhenTelemetryDisabled() throws Exception {
        properties.getTelemetry().setEnabled(false);

        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(hostSpan()).toByteArray()))
                .andExpect(status().isServiceUnavailable());

        assertThat(store.retainedTraceCount()).isZero();
    }

    @Test
    void rejectsOversizePayload() throws Exception {
        properties.getTelemetry().setMaxRequestBytes(8);

        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(new byte[64]))
                .andExpect(status().isPayloadTooLarge());

        assertThat(store.retainedTraceCount()).isZero();
    }

    @Test
    void rejectsInvalidProtobufPayload() throws Exception {
        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(new byte[] {0x7F, 0x7F, 0x7F}))
                .andExpect(status().isBadRequest());

        assertThat(store.retainedTraceCount()).isZero();
    }

    @Test
    void excludesBootUiSelfSpansByDefault() throws Exception {
        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(selfSpan()).toByteArray()))
                .andExpect(status().isOk());

        assertThat(store.retainedTraceCount()).isZero();
    }

    @Test
    void retainsSelfSpansWhenExclusionDisabled() throws Exception {
        properties.getTelemetry().setExcludeSelfSpans(false);

        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(selfSpan()).toByteArray()))
                .andExpect(status().isOk());

        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.allSpansSnapshot()).hasSize(1);
    }

    @Test
    void dropsWholeSelfTraceButKeepsUnrelatedHostTrace() throws Exception {
        // The self request contributes both its path-bearing root and a nested filter-chain span
        // that carries no path; both must be dropped, while the unrelated host trace is kept.
        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(selfFilterChainSpan(), hostSpan(), selfSpan())
                                .toByteArray()))
                .andExpect(status().isOk());

        assertThat(store.findTrace(TRACE_ID)).isNull();
        assertThat(store.allSpansSnapshot()).hasSize(1);
        assertThat(store.allSpansSnapshot().get(0).spanId()).isEqualTo(HOST_SPAN_ID);
    }
}
