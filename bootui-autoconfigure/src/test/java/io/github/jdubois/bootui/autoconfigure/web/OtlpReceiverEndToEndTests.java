package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.google.protobuf.ByteString;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class OtlpReceiverEndToEndTests {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    private static final String CHAT_SPAN_ID = "1111111111111111";

    private static final String TOOL_SPAN_ID = "2222222222222222";

    private static final String VECTOR_SPAN_ID = "3333333333333333";

    private TelemetryStore store;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setEnabled(true);
        store = new TelemetryStore(properties.getTelemetry());
        OtlpSpanDecoder decoder = new OtlpSpanDecoder(properties.getTelemetry());
        OtlpReceiverController receiver = new OtlpReceiverController(store, decoder, properties);
        TracesController traces = new TracesController(store);
        AiController ai = new AiController(store, properties);
        mvc = standaloneSetup(receiver, traces, ai).build();
    }

    @Test
    void receivesProtobufPayloadAndExposesTraceAndAiViews() throws Exception {
        byte[] payload = sampleRequest().toByteArray();

        mvc.perform(post("/bootui/api/otlp/v1/traces")
                .contentType("application/x-protobuf")
                .content(payload))
                .andExpect(status().isOk());

        mvc.perform(get("/bootui/api/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retained").value(1))
                .andExpect(jsonPath("$.traces[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.traces[0].spanCount").value(3))
                .andExpect(jsonPath("$.traces[0].hasAi").value(true))
                .andExpect(jsonPath("$.traces[0].services[0]").value("sample"));

        mvc.perform(get("/bootui/api/traces/" + TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spans.length()").value(3));

        mvc.perform(get("/bootui/api/ai/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChats").value(1))
                .andExpect(jsonPath("$.totalInputTokens").value(42))
                .andExpect(jsonPath("$.totalOutputTokens").value(7))
                .andExpect(jsonPath("$.toolCallCount").value(1))
                .andExpect(jsonPath("$.vectorOperationCount").value(1))
                .andExpect(jsonPath("$.recent[0].requestModel").value("qwen3:0.6b"))
                .andExpect(jsonPath("$.recent[0].provider").value("ollama"));

        mvc.perform(get("/bootui/api/ai/chats/" + CHAT_SPAN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.toolCallCount").value(1))
                .andExpect(jsonPath("$.summary.vectorOperationCount").value(1))
                .andExpect(jsonPath("$.toolCalls[0].name").value("getWeather"))
                .andExpect(jsonPath("$.vectorOperations[0].collectionName").value("docs"));

        mvc.perform(get("/bootui/api/ai/tokens?minutes=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(5));
    }

    @Test
    void receiverRejectsInvalidProtobuf() throws Exception {
        mvc.perform(post("/bootui/api/otlp/v1/traces")
                .contentType("application/x-protobuf")
                .content(new byte[] { 0x7F, 0x7F, 0x7F }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void receiverRejectsOversizePayload() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setMaxRequestBytes(10);
        TelemetryStore tinyStore = new TelemetryStore(properties.getTelemetry());
        OtlpSpanDecoder decoder = new OtlpSpanDecoder(properties.getTelemetry());
        OtlpReceiverController smallReceiver = new OtlpReceiverController(tinyStore, decoder, properties);
        MockMvc tinyMvc = standaloneSetup(smallReceiver).build();
        tinyMvc.perform(post("/bootui/api/otlp/v1/traces")
                .contentType("application/x-protobuf")
                .content(new byte[256]))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void clearEndpointEmptiesStore() throws Exception {
        store.add(decode(sampleRequest()).get(0));
        assertThat(store.retainedTraceCount()).isEqualTo(1);
        mvc.perform(delete("/bootui/api/traces"))
                .andExpect(status().isNoContent());
        assertThat(store.retainedTraceCount()).isZero();
    }

    private java.util.List<io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan> decode(ExportTraceServiceRequest req)
            throws Exception {
        BootUiProperties properties = new BootUiProperties();
        return new OtlpSpanDecoder(properties.getTelemetry()).decode(req.toByteArray());
    }

    private ExportTraceServiceRequest sampleRequest() {
        long now = System.currentTimeMillis() * 1_000_000L;
        ByteString traceId = ByteString.copyFrom(hexToBytes(TRACE_ID));

        Span chat = Span.newBuilder()
                .setTraceId(traceId)
                .setSpanId(ByteString.copyFrom(hexToBytes(CHAT_SPAN_ID)))
                .setName("chat qwen3:0.6b")
                .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(now)
                .setEndTimeUnixNano(now + 250_000_000L)
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                .addAttributes(stringAttr("gen_ai.operation.name", "chat"))
                .addAttributes(stringAttr("gen_ai.system", "ollama"))
                .addAttributes(stringAttr("gen_ai.request.model", "qwen3:0.6b"))
                .addAttributes(stringAttr("gen_ai.response.model", "qwen3:0.6b"))
                .addAttributes(intAttr("gen_ai.usage.input_tokens", 42L))
                .addAttributes(intAttr("gen_ai.usage.output_tokens", 7L))
                .addAttributes(arrayStringAttr("gen_ai.response.finish_reasons", "stop"))
                .build();

        Span tool = Span.newBuilder()
                .setTraceId(traceId)
                .setSpanId(ByteString.copyFrom(hexToBytes(TOOL_SPAN_ID)))
                .setParentSpanId(ByteString.copyFrom(hexToBytes(CHAT_SPAN_ID)))
                .setName("execute_tool getWeather")
                .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                .setStartTimeUnixNano(now + 10_000_000L)
                .setEndTimeUnixNano(now + 30_000_000L)
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                .addAttributes(stringAttr("gen_ai.operation.name", "execute_tool"))
                .addAttributes(stringAttr("gen_ai.tool.name", "getWeather"))
                .build();

        Span vector = Span.newBuilder()
                .setTraceId(traceId)
                .setSpanId(ByteString.copyFrom(hexToBytes(VECTOR_SPAN_ID)))
                .setParentSpanId(ByteString.copyFrom(hexToBytes(CHAT_SPAN_ID)))
                .setName("db query docs")
                .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(now + 5_000_000L)
                .setEndTimeUnixNano(now + 15_000_000L)
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                .addAttributes(stringAttr("db.system", "spring_ai_vector_store"))
                .addAttributes(stringAttr("db.operation.name", "query"))
                .addAttributes(stringAttr("db.collection.name", "docs"))
                .build();

        ScopeSpans scopeSpans = ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName("io.micrometer.observation").build())
                .addSpans(chat)
                .addSpans(tool)
                .addSpans(vector)
                .build();

        ResourceSpans resourceSpans = ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(stringAttr("service.name", "sample")).build())
                .addScopeSpans(scopeSpans)
                .build();

        return ExportTraceServiceRequest.newBuilder().addResourceSpans(resourceSpans).build();
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build();
    }

    private static KeyValue intAttr(String key, long value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setIntValue(value).build())
                .build();
    }

    private static KeyValue arrayStringAttr(String key, String... values) {
        ArrayValue.Builder arr = ArrayValue.newBuilder();
        for (String v : values) {
            arr.addValues(AnyValue.newBuilder().setStringValue(v).build());
        }
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setArrayValue(arr.build()).build())
                .build();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static String s(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
