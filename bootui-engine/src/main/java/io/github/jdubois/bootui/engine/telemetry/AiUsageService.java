package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.dto.AiChatDetailDto;
import io.github.jdubois.bootui.core.dto.AiChatSummaryDto;
import io.github.jdubois.bootui.core.dto.AiOverviewDto;
import io.github.jdubois.bootui.core.dto.AiTokenBucketDto;
import io.github.jdubois.bootui.core.dto.AiTokenSeriesDto;
import io.github.jdubois.bootui.core.dto.AiToolCallDto;
import io.github.jdubois.bootui.core.dto.AiVectorOpDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Framework-neutral read model for the BootUI AI Usage panel. Derives Spring-AI / LangChain4j usage
 * metrics from the OTLP spans accumulated in {@link TelemetryStore}.
 *
 * <p>Live configuration is read through a {@code Supplier<AiUsageSettings>} and the wall clock
 * through a {@link LongSupplier}, so the same engine logic serves both adapters and stays testable.</p>
 */
public final class AiUsageService {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final int MAX_RECENT_CHATS = 500;

    private static final int MAX_TOKEN_SERIES_MINUTES = 240;

    private static final int MAX_MODEL_BREAKDOWN_ENTRIES = 50;

    private final TelemetryStore store;

    private final Supplier<AiUsageSettings> settings;

    private final LongSupplier nowMillis;

    public AiUsageService(TelemetryStore store, Supplier<AiUsageSettings> settings, LongSupplier nowMillis) {
        this.store = store;
        this.settings = settings;
        this.nowMillis = nowMillis;
    }

    public AiOverviewDto overview() {
        AiUsageSettings config = settings.get();
        List<NormalizedSpan> chats = chatSpansSorted();
        long totalIn = 0;
        long totalOut = 0;
        long totalDuration = 0;
        int errorCount = 0;
        Map<String, Long> tokensByModel = new LinkedHashMap<>();
        Map<String, Integer> callsByModel = new LinkedHashMap<>();
        for (NormalizedSpan chat : chats) {
            Long in = AiSpanRecognizer.inputTokens(chat);
            Long out = AiSpanRecognizer.outputTokens(chat);
            totalIn += in == null ? 0 : in;
            totalOut += out == null ? 0 : out;
            totalDuration += chat.durationNanos();
            if (chat.isError()) {
                errorCount++;
            }
            String model = preferredModel(chat);
            long modelTokens = (in == null ? 0 : in) + (out == null ? 0 : out);
            tokensByModel.merge(model, modelTokens, Long::sum);
            callsByModel.merge(model, 1, Integer::sum);
        }
        int toolCount = 0;
        int vectorCount = 0;
        int embeddingCount = 0;
        for (NormalizedSpan span : store.allSpansSnapshot()) {
            if (AiSpanRecognizer.isToolCall(span)) {
                toolCount++;
            }
            if (AiSpanRecognizer.isVectorOperation(span)) {
                vectorCount++;
            }
            if (AiSpanRecognizer.isEmbedding(span)) {
                embeddingCount++;
            }
        }
        int recentLimit = Math.min(clamp(config.maxRecentChats(), 0, MAX_RECENT_CHATS), chats.size());
        List<AiChatSummaryDto> recent = new ArrayList<>(recentLimit);
        for (int i = 0; i < recentLimit; i++) {
            recent.add(toSummary(chats.get(i)));
        }
        boolean telemetryEnabled = config.telemetryEnabled();
        boolean springAi = AiFrameworkDetector.isSpringAiPresent();
        boolean langChain4j = AiFrameworkDetector.isLangChain4jPresent();
        boolean frameworkDetected = springAi || langChain4j;
        String banner = telemetryEnabled && frameworkDetected && config.showContentCaptureBanner()
                ? overviewContentBanner(springAi)
                : null;
        return new AiOverviewDto(
                telemetryEnabled,
                springAi,
                langChain4j,
                chats.size(),
                totalIn,
                totalOut,
                topLongEntries(tokensByModel),
                topIntegerEntries(callsByModel),
                errorCount,
                chats.isEmpty() ? 0 : totalDuration / chats.size(),
                toolCount,
                vectorCount,
                embeddingCount,
                recent,
                banner);
    }

    public List<AiChatSummaryDto> chats(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<NormalizedSpan> chats = chatSpansSorted();
        List<AiChatSummaryDto> out = new ArrayList<>(Math.min(safeLimit, chats.size()));
        for (int i = 0; i < chats.size() && i < safeLimit; i++) {
            out.add(toSummary(chats.get(i)));
        }
        return out;
    }

    public Optional<AiChatDetailDto> chatDetail(String spanId) {
        for (NormalizedSpan span : store.allSpansSnapshot()) {
            if (AiSpanRecognizer.isChat(span) && spanId.equals(span.spanId())) {
                return Optional.of(buildDetail(span));
            }
        }
        return Optional.empty();
    }

    public AiTokenSeriesDto tokens(Integer minutes) {
        int requestedWindow = minutes != null ? minutes : settings.get().tokenSeriesMinutes();
        int window = clamp(requestedWindow, 1, MAX_TOKEN_SERIES_MINUTES);
        long endMinute = nowMillis.getAsLong() / 60_000L;
        long startMinute = endMinute - window + 1;
        TreeMap<Long, long[]> buckets = new TreeMap<>(); // [in, out, calls]
        for (long m = startMinute; m <= endMinute; m++) {
            buckets.put(m, new long[3]);
        }
        for (NormalizedSpan span : store.allSpansSnapshot()) {
            if (!AiSpanRecognizer.isChat(span)) {
                continue;
            }
            long minute = (span.startEpochNanos() / NANOS_PER_SECOND) / 60L;
            if (minute < startMinute || minute > endMinute) {
                continue;
            }
            long[] bucket = buckets.get(minute);
            if (bucket == null) {
                continue;
            }
            Long in = AiSpanRecognizer.inputTokens(span);
            Long out = AiSpanRecognizer.outputTokens(span);
            bucket[0] += in == null ? 0 : in;
            bucket[1] += out == null ? 0 : out;
            bucket[2] += 1;
        }
        List<AiTokenBucketDto> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, long[]> entry : buckets.entrySet()) {
            long[] v = entry.getValue();
            out.add(new AiTokenBucketDto(entry.getKey(), v[0], v[1], (int) v[2]));
        }
        return new AiTokenSeriesDto(window, out);
    }

    private static String preferredModel(NormalizedSpan chat) {
        String model = AiSpanRecognizer.responseModel(chat);
        if (model == null || model.isBlank()) {
            model = AiSpanRecognizer.requestModel(chat);
        }
        return model == null || model.isBlank() ? "unknown" : model;
    }

    private static Map<String, Long> topLongEntries(Map<String, Long> input) {
        return input.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(MAX_MODEL_BREAKDOWN_ENTRIES)
                .collect(
                        LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }

    private static Map<String, Integer> topIntegerEntries(Map<String, Integer> input) {
        return input.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_MODEL_BREAKDOWN_ENTRIES)
                .collect(
                        LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String overviewContentBanner(boolean springAi) {
        if (springAi) {
            return "Prompt and completion text is not captured by default. Enable Spring AI's "
                    + "include-prompt / include-completion observation options (or attach a custom "
                    + "ObservationFilter) to see message content in the conversation drawer.";
        }
        return "Prompt and completion text is not captured by default. Enable your AI framework's "
                + "content-capture option (for LangChain4j, capture GenAI message content on the "
                + "OpenTelemetry instrumentation) to see message content in the conversation drawer.";
    }

    private List<NormalizedSpan> chatSpansSorted() {
        List<NormalizedSpan> chats = new ArrayList<>();
        for (NormalizedSpan span : store.allSpansSnapshot()) {
            if (AiSpanRecognizer.isChat(span)) {
                chats.add(span);
            }
        }
        chats.sort(Comparator.comparingLong(NormalizedSpan::startEpochNanos).reversed());
        return chats;
    }

    private AiChatSummaryDto toSummary(NormalizedSpan chat) {
        int toolCalls = 0;
        int vectorOps = 0;
        TelemetryStore.TraceBucket bucket = store.findTrace(chat.traceId());
        if (bucket != null) {
            for (NormalizedSpan sibling : bucket.spans()) {
                if (AiSpanRecognizer.isToolCall(sibling)) {
                    toolCalls++;
                }
                if (AiSpanRecognizer.isVectorOperation(sibling)) {
                    vectorOps++;
                }
            }
        }
        Long in = AiSpanRecognizer.inputTokens(chat);
        Long out = AiSpanRecognizer.outputTokens(chat);
        Long total = AiSpanRecognizer.totalTokens(chat);
        return new AiChatSummaryDto(
                chat.traceId(),
                chat.spanId(),
                chat.startEpochNanos(),
                chat.durationNanos(),
                AiSpanRecognizer.provider(chat),
                AiSpanRecognizer.requestModel(chat),
                AiSpanRecognizer.responseModel(chat),
                in,
                out,
                total,
                AiSpanRecognizer.finishReason(chat),
                chat.statusCode(),
                "chat",
                toolCalls,
                vectorOps);
    }

    private AiChatDetailDto buildDetail(NormalizedSpan chat) {
        TelemetryStore.TraceBucket bucket = store.findTrace(chat.traceId());
        List<AiToolCallDto> tools = new ArrayList<>();
        List<AiVectorOpDto> vectors = new ArrayList<>();
        if (bucket != null) {
            for (NormalizedSpan sibling : bucket.spans()) {
                if (AiSpanRecognizer.isToolCall(sibling)) {
                    tools.add(new AiToolCallDto(
                            sibling.spanId(),
                            AiSpanRecognizer.toolName(sibling),
                            sibling.startEpochNanos(),
                            sibling.durationNanos(),
                            sibling.statusCode()));
                }
                if (AiSpanRecognizer.isVectorOperation(sibling)) {
                    vectors.add(new AiVectorOpDto(
                            sibling.spanId(),
                            AiSpanRecognizer.vectorOperation(sibling),
                            AiSpanRecognizer.vectorCollection(sibling),
                            sibling.startEpochNanos(),
                            sibling.durationNanos(),
                            sibling.statusCode()));
                }
            }
        }
        tools.sort(Comparator.comparingLong(AiToolCallDto::startEpochNanos));
        vectors.sort(Comparator.comparingLong(AiVectorOpDto::startEpochNanos));
        boolean contentCaptured = chat.attributes() != null
                && (chat.attributes().containsKey("gen_ai.prompt")
                        || chat.attributes().containsKey("gen_ai.completion")
                        || chat.attributes().containsKey("gen_ai.input.messages")
                        || chat.attributes().containsKey("gen_ai.output.messages"));
        String banner = !contentCaptured && settings.get().showContentCaptureBanner()
                ? "Message content is not on this span. Enable your AI framework's content-capture option "
                        + "(Spring AI's include-prompt / include-completion observation options, or LangChain4j's "
                        + "GenAI message-content capture on the OpenTelemetry instrumentation) to capture prompt "
                        + "and completion text."
                : null;
        return new AiChatDetailDto(
                toSummary(chat),
                tools,
                vectors,
                SpanMappers.toAttributeList(chat.attributes()),
                SpanMappers.toEventList(chat.events()),
                contentCaptured,
                banner);
    }
}
