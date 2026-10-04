package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.google.protobuf.ByteString;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
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

    private static final String TRACE_ID_2 = "abababababababababababababababab";

    private static final String SPAN_ID_2 = "4444444444444444";

    private static final String SPAN_ID_3 = "5555555555555555";

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
        return request("sample", spans);
    }

    private static ExportTraceServiceRequest request(String serviceName, Span... spans) {
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
                                .addAttributes(stringAttr("service.name", serviceName))
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

    private static Span aiSpan(String traceId, String spanId) {
        return hostSpan().toBuilder()
                .setTraceId(bytes(traceId))
                .setSpanId(bytes(spanId))
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
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "sample");
        mvc = standaloneSetup(new OtlpReceiverController(
                        store, decoder, properties, BootUiSelfDataFilter.defaults(), environment))
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
    void importedApplicationAiSpansReachTheJournalFromTheReceiversBootUiScopeButOtherServicesAndSelfTracesDoNot()
            throws Exception {
        List<RuntimeEvent> imported = new ArrayList<>();
        List<RuntimeEvent> offered = new ArrayList<>();
        store.setRuntimeEventSink(new RuntimeEventSink() {
            @Override
            public boolean offer(RuntimeEvent event) {
                offered.add(event);
                return true;
            }

            @Override
            public boolean offerImported(RuntimeEvent event) {
                imported.add(event);
                return true;
            }
        });

        // The adapters' correlation filters run the receiver in BootUI's own scope.
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            mvc.perform(post("/bootui/api/otlp/v1/traces")
                            .contentType("application/x-protobuf")
                            .content(request(hostSpan()).toByteArray()))
                    .andExpect(status().isOk());
            // A cooperating local process exports under its own service name: its AI call is not this application's.
            mvc.perform(post("/bootui/api/otlp/v1/traces")
                            .contentType("application/x-protobuf")
                            .content(request("other-service", aiSpan(TRACE_ID_2, SPAN_ID_2))
                                    .toByteArray()))
                    .andExpect(status().isOk());
            // An AI call ending, so exported, ahead of BootUI's own request span: the whole trace is BootUI's own.
            mvc.perform(post("/bootui/api/otlp/v1/traces")
                            .contentType("application/x-protobuf")
                            .content(request(aiSpan(TRACE_ID, SPAN_ID_3), selfSpan())
                                    .toByteArray()))
                    .andExpect(status().isOk());
        }

        assertThat(offered).isEmpty();
        assertThat(imported).extracting(RuntimeEvent::traceId).containsExactly(HOST_TRACE_ID);
        assertThat(store.findTrace(TRACE_ID_2))
                .as("still stored for the Traces panel")
                .isNotNull();
    }

    @Test
    void theApplicationsServiceNameIsResolvedAsSpringBootResolvesItsOpenTelemetryResource() {
        Map<String, String> variables = new HashMap<>();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "orders")
                .withProperty("OTEL_SERVICE_NAME", "ignored-as-a-property");
        assertThat(OtlpReceiverController.applicationServiceName(environment, variables::get))
                .isEqualTo("orders");

        variables.put("OTEL_RESOURCE_ATTRIBUTES", "deployment.environment=dev, service.name = orders%20attributes");
        assertThat(OtlpReceiverController.applicationServiceName(environment, variables::get))
                .isEqualTo("orders attributes");

        variables.put("OTEL_SERVICE_NAME", "orders-env");
        assertThat(OtlpReceiverController.applicationServiceName(environment, variables::get))
                .isEqualTo("orders-env");

        environment.setProperty("management.opentelemetry.resource-attributes.service.name", "orders-api");
        assertThat(OtlpReceiverController.applicationServiceName(environment, variables::get))
                .isEqualTo("orders-api");

        environment.setProperty("management.opentelemetry.resource-attributes.service.name", "");
        assertThat(OtlpReceiverController.applicationServiceName(environment, variables::get))
                .as("an explicitly empty name wins, as in Boot, and fails closed")
                .isNull();

        assertThat(OtlpReceiverController.applicationServiceName(new MockEnvironment(), name -> null))
                .as("Boot's unknown_service default names no application")
                .isNull();
        assertThat(OtlpReceiverController.applicationServiceName(null)).isNull();
    }

    @Test
    void anAiSpanOfABootUiTraceInTheBatchStaysOutOfTheJournalEvenBeyondTheStoresSelfTraceMemory() throws Exception {
        List<RuntimeEvent> imported = new ArrayList<>();
        store.setRuntimeEventSink(new RuntimeEventSink() {
            @Override
            public boolean offer(RuntimeEvent event) {
                imported.add(event);
                return true;
            }

            @Override
            public boolean offerImported(RuntimeEvent event) {
                imported.add(event);
                return true;
            }
        });
        String firstSelfTrace = String.format("%032x", 1);
        List<Span> spans = new ArrayList<>();
        spans.add(aiSpan(firstSelfTrace, SPAN_ID_3));
        for (int i = 1; i <= 4_097; i++) {
            spans.add(selfSpan().toBuilder()
                    .setTraceId(bytes(String.format("%032x", i)))
                    .build());
        }

        mvc.perform(post("/bootui/api/otlp/v1/traces")
                        .contentType("application/x-protobuf")
                        .content(request(spans.toArray(Span[]::new)).toByteArray()))
                .andExpect(status().isOk());

        assertThat(imported).isEmpty();
        assertThat(store.retainedTraceCount()).isZero();
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
                .andExpect(status().isContentTooLarge());

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
