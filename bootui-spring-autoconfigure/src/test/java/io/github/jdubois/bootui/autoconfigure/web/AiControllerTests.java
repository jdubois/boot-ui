package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link AiController}.
 *
 * <p>The controller derives the AI Usage panel from the GenAI spans accumulated
 * in {@link TelemetryStore}, so the tests seed normalized spans directly and
 * assert the stable DTO JSON shape. They cover the aggregated overview, the
 * disabled/empty branches, the recent-chats list (limit and ordering), the chat
 * detail (tools and vector operations, plus the not-found path) and the token
 * time series (window sizing and clamping).</p>
 */
class AiControllerTests {

    private static final long ONE_MINUTE_NANOS = 60L * 1_000_000_000L;

    private static long nowNanos() {
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static NormalizedSpan chatSpan(
            String traceId,
            String spanId,
            long startNanos,
            long durationNanos,
            String provider,
            String model,
            long inputTokens,
            long outputTokens,
            boolean error) {
        Map<String, AttributeValue> attrs = new LinkedHashMap<>();
        attrs.put("gen_ai.operation.name", AttributeValue.ofString("chat"));
        attrs.put("gen_ai.system", AttributeValue.ofString(provider));
        attrs.put("gen_ai.request.model", AttributeValue.ofString(model));
        attrs.put("gen_ai.response.model", AttributeValue.ofString(model));
        attrs.put("gen_ai.usage.input_tokens", AttributeValue.ofNumber(inputTokens));
        attrs.put("gen_ai.usage.output_tokens", AttributeValue.ofNumber(outputTokens));
        attrs.put("gen_ai.response.finish_reasons", AttributeValue.ofList(List.of("stop")));
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "chat " + model,
                "CLIENT",
                "sample",
                "io.micrometer.observation",
                startNanos,
                startNanos + durationNanos,
                error ? "ERROR" : "OK",
                null,
                attrs,
                List.of());
    }

    private static NormalizedSpan toolSpan(String traceId, String spanId, String parentSpanId, String toolName) {
        Map<String, AttributeValue> attrs = new LinkedHashMap<>();
        attrs.put("gen_ai.operation.name", AttributeValue.ofString("execute_tool"));
        attrs.put("gen_ai.tool.name", AttributeValue.ofString(toolName));
        return new NormalizedSpan(
                traceId,
                spanId,
                parentSpanId,
                "execute_tool " + toolName,
                "INTERNAL",
                "sample",
                "io.micrometer.observation",
                10L,
                15L,
                "OK",
                null,
                attrs,
                List.of());
    }

    private static NormalizedSpan vectorSpan(String traceId, String spanId, String parentSpanId, String collection) {
        Map<String, AttributeValue> attrs = new LinkedHashMap<>();
        attrs.put("db.system", AttributeValue.ofString("spring_ai_vector_store"));
        attrs.put("db.operation.name", AttributeValue.ofString("query"));
        attrs.put("db.collection.name", AttributeValue.ofString(collection));
        return new NormalizedSpan(
                traceId,
                spanId,
                parentSpanId,
                "db query " + collection,
                "CLIENT",
                "sample",
                "io.micrometer.observation",
                5L,
                12L,
                "OK",
                null,
                attrs,
                List.of());
    }

    private static MockMvc mvcWith(TelemetryStore store, BootUiProperties properties) {
        return standaloneSetup(new AiController(store, properties)).build();
    }

    @Test
    void overviewAggregatesChatToolAndVectorSpans() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        long now = nowNanos();
        store.add(chatSpan("trace-1", "chat-1", now, 250_000_000L, "ollama", "qwen3", 42, 8, false));
        store.add(toolSpan("trace-1", "tool-1", "chat-1", "getWeather"));
        store.add(vectorSpan("trace-1", "vector-1", "chat-1", "docs"));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.springAiDetected").value(false))
                .andExpect(jsonPath("$.langChain4jDetected").value(false))
                .andExpect(jsonPath("$.totalChats").value(1))
                .andExpect(jsonPath("$.totalInputTokens").value(42))
                .andExpect(jsonPath("$.totalOutputTokens").value(8))
                .andExpect(jsonPath("$.tokensByModel.qwen3").value(50))
                .andExpect(jsonPath("$.callsByModel.qwen3").value(1))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.averageDurationNanos").value(250_000_000L))
                .andExpect(jsonPath("$.toolCallCount").value(1))
                .andExpect(jsonPath("$.vectorOperationCount").value(1))
                .andExpect(jsonPath("$.embeddingCount").value(0))
                .andExpect(jsonPath("$.recent.length()").value(1))
                .andExpect(jsonPath("$.recent[0].provider").value("ollama"))
                .andExpect(jsonPath("$.recent[0].requestModel").value("qwen3"))
                .andExpect(jsonPath("$.recent[0].toolCallCount").value(1))
                .andExpect(jsonPath("$.recent[0].vectorOperationCount").value(1));
    }

    @Test
    void overviewReportsDisabledTelemetry() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getTelemetry().setEnabled(false);
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.totalChats").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));
    }

    @Test
    void overviewIsEmptyWhenNoSpans() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.totalChats").value(0))
                .andExpect(jsonPath("$.totalInputTokens").value(0))
                .andExpect(jsonPath("$.totalOutputTokens").value(0))
                .andExpect(jsonPath("$.averageDurationNanos").value(0))
                .andExpect(jsonPath("$.toolCallCount").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));
    }

    @Test
    void chatsRespectLimitAndAreOrderedMostRecentFirst() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        long now = nowNanos();
        store.add(chatSpan("trace-old", "chat-old", now, 1_000_000L, "ollama", "m1", 1, 1, false));
        store.add(chatSpan("trace-new", "chat-new", now + ONE_MINUTE_NANOS, 1_000_000L, "ollama", "m2", 2, 2, false));
        MockMvc mvc = mvcWith(store, properties);

        mvc.perform(get("/bootui/api/ai/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].spanId").value("chat-new"))
                .andExpect(jsonPath("$[1].spanId").value("chat-old"));

        mvc.perform(get("/bootui/api/ai/chats").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].spanId").value("chat-new"));

        mvc.perform(get("/bootui/api/ai/chats").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void chatDetailReturnsLinkedToolsAndVectorOperations() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        long now = nowNanos();
        store.add(chatSpan("trace-1", "chat-1", now, 100_000_000L, "ollama", "qwen3", 10, 5, false));
        store.add(toolSpan("trace-1", "tool-1", "chat-1", "getWeather"));
        store.add(vectorSpan("trace-1", "vector-1", "chat-1", "docs"));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/chats/chat-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.spanId").value("chat-1"))
                .andExpect(jsonPath("$.summary.toolCallCount").value(1))
                .andExpect(jsonPath("$.summary.vectorOperationCount").value(1))
                .andExpect(jsonPath("$.toolCalls.length()").value(1))
                .andExpect(jsonPath("$.toolCalls[0].name").value("getWeather"))
                .andExpect(jsonPath("$.vectorOperations.length()").value(1))
                .andExpect(jsonPath("$.vectorOperations[0].operation").value("query"))
                .andExpect(jsonPath("$.vectorOperations[0].collectionName").value("docs"))
                .andExpect(jsonPath("$.contentCaptured").value(false));
    }

    @Test
    void chatDetailReturnsNotFoundForUnknownSpan() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/chats/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokensReturnsRequestedWindowAndBucketsTheChat() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        store.add(chatSpan("trace-1", "chat-1", nowNanos(), 1_000_000L, "ollama", "qwen3", 42, 8, false));

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/tokens").param("minutes", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(5))
                .andExpect(jsonPath("$.buckets.length()").value(5))
                .andExpect(jsonPath("$.buckets[?(@.inputTokens == 42 && @.outputTokens == 8 && @.callCount == 1)]")
                        .exists());
    }

    @Test
    void tokensClampsWindowAndFallsBackToConfiguredDefault() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        MockMvc mvc = mvcWith(store, properties);

        mvc.perform(get("/bootui/api/ai/tokens").param("minutes", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(240));

        mvc.perform(get("/bootui/api/ai/tokens").param("minutes", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(1));

        properties.getAi().setTokenSeriesMinutes(7);
        mvc.perform(get("/bootui/api/ai/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(7));
    }

    @Test
    void chatsClampLimitToConfiguredMaximum() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(new SpringTelemetrySettings(properties));
        long now = nowNanos();
        // 501 chat spans split across two traces stay under the per-trace and trace caps
        // while exceeding the 500 response limit, so the clamp is what bounds the result.
        for (int i = 0; i < 501; i++) {
            String traceId = "bulk-" + (i % 2);
            store.add(chatSpan(traceId, "chat-" + i, now + i, 1_000_000L, "ollama", "m", 1, 1, false));
        }

        mvcWith(store, properties)
                .perform(get("/bootui/api/ai/chats").param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(500));
    }
}
