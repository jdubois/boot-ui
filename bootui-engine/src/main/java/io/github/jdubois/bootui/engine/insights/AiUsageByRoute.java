package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code ai-usage-by-route} ({@code docs/PLAN-v2.md} §5.5): the AI operations each route's requests or jobs made, each
 * stamped with its request or execution when the AI framework reported it (M3-9), or else linked by its GenAI span's
 * trace id: model calls per request, which reveals agent loops, latency, errors, tokens with their coverage, input
 * growth across a request's successive model calls, and calls stopped at the length limit. Never a money figure.
 */
public final class AiUsageByRoute implements Observation {

    public static final String KIND = "ai-usage-by-route";

    static final int MIN_OPERATIONS = 3;

    /** The tokens of one model call above which a single call is enough to report the route. */
    public static final long DEFAULT_TOKEN_THRESHOLD = 8_000;

    private final long tokenThreshold;

    public AiUsageByRoute() {
        this(DEFAULT_TOKEN_THRESHOLD);
    }

    /** @param tokenThreshold the tokens of one model call above which a single call is enough to report the route */
    public AiUsageByRoute(long tokenThreshold) {
        this.tokenThreshold = validateTokenThreshold(tokenThreshold);
    }

    /**
     * {@code tokenThreshold} itself, rejecting a non-positive one. A threshold of zero or less would report every
     * route from a single model call, so it is a configuration mistake rather than a weaker setting, and every stack
     * says so with the same message instead of silently substituting the default.
     *
     * @throws IllegalArgumentException when {@code tokenThreshold} is not positive
     */
    public static long validateTokenThreshold(long tokenThreshold) {
        if (tokenThreshold < 1) {
            throw new IllegalArgumentException("bootui.runtime-insights.ai-token-threshold must be positive.");
        }
        return tokenThreshold;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "AI usage by route";
    }

    /** Trace id for operations recovered from GenAI spans; each finding reports the tier its operations used. */
    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.TRACE_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.AI);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        boolean traced = snapshot.requests().stream().anyMatch(request -> request.traceId() != null);
        long[] ai = snapshot.coverage().get(JournalSource.AI);
        // An AI framework BootUI listens to stamps each call with its request or execution, without tracing (M3-9).
        boolean stamped = ai != null && ai[0] + ai[1] > 0;
        return snapshot.requests().isEmpty() || traced || stamped
                ? null
                : "Without tracing, or Spring AI or Quarkus LangChain4j reporting its calls, AI usage is unknown.";
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Usage usage = new Usage(tokenThreshold);
            for (ProjectedRequest request : route.getValue()) {
                List<RuntimeEvent> operations = request.children(JournalSource.AI);
                if (!operations.isEmpty()) {
                    usage.add(request, operations);
                }
            }
            eligible += route.getValue().size();
            if (usage.operations > 0) {
                findings.add(finding(route.getKey(), usage, route.getValue().size()));
            }
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, Usage usage, long eligible) {
        boolean sufficient = usage.operations >= MIN_OPERATIONS || usage.lengthLimited > 0 || usage.overThreshold > 0;
        StringBuilder sentence = new StringBuilder("`" + route + "` made "
                + InsightText.counted(usage.operations, "AI operation") + " in " + usage.requests.size() + " of "
                + InsightText.counted(eligible, InsightText.unit(route)));
        if (usage.modelCalls > 0) {
            sentence.append(": ")
                    .append(InsightText.counted(usage.modelCalls, "model call"))
                    .append(", up to ")
                    .append(usage.mostCallsInOneRequest)
                    .append(" in one request, a median ")
                    .append(InsightText.millis(RouteTimeBreakdown.median(usage.chatNanos())))
                    .append(" ms each");
            if (usage.callsWithTokens > 0) {
                sentence.append(", ")
                        .append(usage.inputTokens)
                        .append(" input and ")
                        .append(usage.outputTokens)
                        .append(" output tokens (reported by ")
                        .append(usage.callsWithTokens)
                        .append(" of ")
                        .append(usage.modelCalls)
                        .append(" calls)");
            }
        }
        sentence.append('.');
        if (usage.growingRequests > 0) {
            sentence.append(" Input tokens grew across successive model calls in ")
                    .append(InsightText.counted(usage.growingRequests, "request"))
                    .append(", up to ")
                    .append(String.format(Locale.ROOT, "%.1f", usage.largestGrowth))
                    .append(" times the first call's.");
        }
        if (usage.lengthLimited > 0) {
            sentence.append(' ')
                    .append(InsightText.counted(usage.lengthLimited, "call"))
                    .append(" stopped at the length limit.");
        }
        if (usage.failed > 0) {
            sentence.append(' ')
                    .append(InsightText.counted(usage.failed, "operation"))
                    .append(" failed.");
        }
        if (!sufficient) {
            sentence.append(" Reported from " + MIN_OPERATIONS + " operations, one call above " + tokenThreshold
                    + " tokens, or one length-limited stop.");
        }
        List<String> checks = new ArrayList<>();
        if (usage.mostCallsInOneRequest > 2) {
            checks.add("Several model calls in one request suggest an agent or tool loop: check that it ends and"
                    + " caps its iterations.");
        }
        if (usage.growingRequests > 0) {
            checks.add("Input that grows with each call resends the conversation: trim or summarize what is sent.");
        }
        if (usage.lengthLimited > 0) {
            checks.add("A length-limited answer is cut off: raise the output limit or ask for a shorter answer.");
        }
        if (checks.isEmpty()) {
            checks.add("Open an exemplar request's trace to see each operation in order.");
        }
        List<String> limitations = new ArrayList<>();
        if (usage.traceLinked > 0) {
            limitations.add(InsightText.counted(usage.traceLinked, "operation") + " of " + usage.operations
                    + " came from GenAI spans, linked to " + (usage.traceLinked == 1 ? "its" : "their")
                    + " request by trace id and time; such an operation outside a traced request is not counted.");
        }
        if (usage.callsWithTokens < usage.modelCalls) {
            limitations.add("Some model calls reported no tokens, so token totals are a floor.");
        }
        return new Finding(
                route,
                route,
                sufficient,
                sentence.toString(),
                eligible,
                usage.requests.size(),
                checks.size() > 3 ? checks.subList(0, 3) : checks,
                usage.exemplars(),
                List.of("Request", "Operations", "Model calls", "Models", "Input tokens", "Output tokens"),
                usage.rows,
                limitations,
                usage.tier());
    }

    private static final class Usage {

        private final long tokenThreshold;
        private final List<ProjectedRequest> requests = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();
        private final List<Long> chats = new ArrayList<>();
        private final Map<String, Integer> callsPerRequest = new LinkedHashMap<>();
        private long operations;
        private long modelCalls;
        private long callsWithTokens;
        private long inputTokens;
        private long outputTokens;
        private long lengthLimited;
        private long overThreshold;
        private long failed;
        private long traceLinked;
        private long propagated;
        private long growingRequests;
        private double largestGrowth;
        private int mostCallsInOneRequest;

        Usage(long tokenThreshold) {
            this.tokenThreshold = tokenThreshold;
        }

        void add(ProjectedRequest request, List<RuntimeEvent> events) {
            requests.add(request);
            int calls = 0;
            long requestIn = 0;
            long requestOut = 0;
            Long firstInput = null;
            Long lastInput = null;
            List<String> models = new ArrayList<>();
            for (RuntimeEvent event : events) {
                if (!(event.payload() instanceof AiPayload ai)) {
                    continue;
                }
                operations++;
                if (event.requestId() == null && event.executionId() == null) {
                    traceLinked++;
                } else if (event.requestId() != null && ExecutionIds.isAsync(event.executionId())) {
                    propagated++;
                }
                if (ai.failed()) {
                    failed++;
                }
                if (!AiPayload.CHAT.equals(ai.operation())) {
                    continue;
                }
                calls++;
                modelCalls++;
                chats.add(Math.max(0, event.durationNanos()));
                if (ai.model() != null && !models.contains(ai.model())) {
                    models.add(ai.model());
                }
                if (ai.inputTokens() != null || ai.outputTokens() != null) {
                    callsWithTokens++;
                }
                long in = ai.inputTokens() == null ? 0 : ai.inputTokens();
                long out = ai.outputTokens() == null ? 0 : ai.outputTokens();
                requestIn += in;
                requestOut += out;
                if (in + out > tokenThreshold) {
                    overThreshold++;
                }
                if (ai.lengthLimited()) {
                    lengthLimited++;
                }
                if (ai.inputTokens() != null) {
                    if (firstInput == null) {
                        firstInput = ai.inputTokens();
                    }
                    lastInput = ai.inputTokens();
                }
            }
            inputTokens += requestIn;
            outputTokens += requestOut;
            mostCallsInOneRequest = Math.max(mostCallsInOneRequest, calls);
            callsPerRequest.put(request.requestId(), calls);
            if (calls >= 2 && firstInput != null && firstInput > 0 && lastInput > firstInput) {
                growingRequests++;
                largestGrowth = Math.max(largestGrowth, (double) lastInput / firstInput);
            }
            rows.add(List.of(
                    request.requestId(),
                    String.valueOf(events.size()),
                    String.valueOf(calls),
                    String.join(", ", models),
                    String.valueOf(requestIn),
                    String.valueOf(requestOut)));
        }

        /** The weakest tier its operations were joined at: trace id, a propagated task, or the request's own id. */
        CorrelationTier tier() {
            return traceLinked > 0
                    ? CorrelationTier.TRACE_ID
                    : propagated > 0 ? CorrelationTier.PROPAGATED : CorrelationTier.REQUEST_ID;
        }

        long[] chatNanos() {
            return chats.stream().mapToLong(Long::longValue).toArray();
        }

        List<String> exemplars() {
            return callsPerRequest.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }
}
