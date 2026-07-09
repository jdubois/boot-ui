package io.github.jdubois.bootui.engine.restclienttrace;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceStatsDto;
import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * In-memory, bounded buffer of recently made outbound HTTP client calls (Spring {@code RestClient}, {@code
 * RestTemplate}, and {@code WebClient}).
 *
 * <p>Mirrors {@code SqlTraceRecorder}'s shape: thread-safe, capped at {@code maxEntries}, evicts the oldest
 * call once full, and can be paused/resumed at runtime via {@link #setRecording(boolean)} without removing
 * the client instrumentation. The recorder also tracks which client types were actually instrumented, so
 * the panel can distinguish "no HTTP client instrumented yet" from "tracing disabled".</p>
 *
 * <p>Unlike SQL bound parameters (only ever exposed at all when capture is explicitly enabled), query
 * parameter and header values here carry a name BootUI can check, so there's no reason to withhold
 * non-sensitive values wholesale. {@link #record} stores them truncated but otherwise raw; masking is
 * applied per-name at {@link #report(boolean, ValueExposure)}/{@link #topCalls(boolean, ValueExposure)}
 * time (mirroring {@code HttpExchangesService}), so a runtime change to {@code bootui.expose-values} /
 * {@code bootui.mask-secrets} is reflected immediately, including for already-captured calls. Only
 * capturing headers at all is opt-in ({@link #isCaptureHeaders()}); request/response bodies are never
 * captured.</p>
 */
public final class RestClientTraceRecorder implements IdleReclaimable {

    static final int TOP_CALLS_LIMIT = 20;

    /**
     * Header names treated as sensitive even though {@link SecretMasker#isSecret(String)} doesn't
     * recognize them by keyword (mirrors {@code HttpExchangesService}'s allow-list for inbound
     * exchanges).
     */
    private static final Set<String> SENSITIVE_HEADER_NAMES =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-xsrf-token", "x-csrf-token");

    /**
     * A single immutable captured outbound HTTP call. Query parameter values in {@code uri} and header
     * values in {@code requestHeaders} are truncated but otherwise raw; masking is applied at display time
     * (see the class-level docs), not when this record is created.
     */
    public record CapturedCall(
            long id,
            long timestamp,
            String method,
            String uri,
            String host,
            String path,
            Integer status,
            long durationMillis,
            boolean success,
            String errorMessage,
            String clientType,
            Map<String, String> requestHeaders,
            String thread,
            String traceId,
            String callSite) {

        public CapturedCall {
            requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        }
    }

    private final boolean enabled;
    private final boolean captureHeaders;
    private final boolean captureCallSite;
    private final int maxEntries;
    private final long slowCallThresholdMillis;
    private final int maxUriLength;
    private final int maxHeaderValueLength;
    private final int chattyCallThreshold;

    private final Deque<CapturedCall> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final Set<String> clientTypes = new ConcurrentSkipListSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile TraceIdProvider traceIdProvider = RestClientTraceRecorder::mdcTraceId;
    private final SecretMasker masker = new SecretMasker();

    public RestClientTraceRecorder(
            boolean enabled,
            boolean recording,
            boolean captureHeaders,
            boolean captureCallSite,
            int maxEntries,
            long slowCallThresholdMillis,
            int maxUriLength,
            int maxHeaderValueLength,
            int chattyCallThreshold) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.captureHeaders = captureHeaders;
        this.captureCallSite = captureCallSite;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowCallThresholdMillis = Math.max(0, slowCallThresholdMillis);
        this.maxUriLength = Math.max(16, maxUriLength);
        this.maxHeaderValueLength = Math.max(8, maxHeaderValueLength);
        this.chattyCallThreshold = Math.max(2, chattyCallThreshold);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void setRecording(boolean value) {
        boolean changed = recording.getAndSet(value) != value;
        if (changed) {
            notifyListeners();
        }
    }

    public boolean isCaptureHeaders() {
        return captureHeaders;
    }

    public boolean isCaptureCallSite() {
        return captureCallSite;
    }

    /**
     * Replaces the trace-id source used to stamp each captured call. Defaults to the SLF4J MDC {@code
     * traceId} key that Micrometer Tracing publishes on Spring, which works because Spring MVC serves a
     * request start-to-finish on one thread for {@code RestClient}/{@code RestTemplate}. Passing {@code
     * null} restores the default MDC lookup.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider == null ? RestClientTraceRecorder::mdcTraceId : traceIdProvider;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getSlowCallThresholdMillis() {
        return slowCallThresholdMillis;
    }

    public int getChattyCallThreshold() {
        return chattyCallThreshold;
    }

    public boolean isSlow(long durationMillis) {
        return slowCallThresholdMillis > 0 && durationMillis >= slowCallThresholdMillis;
    }

    /** Remembers that a client type (RestClient, RestTemplate, or WebClient) was instrumented. */
    public void registerClientCustomization(String clientType) {
        if (clientType != null && !clientType.isBlank()) {
            clientTypes.add(clientType);
        }
    }

    public List<String> clientTypes() {
        return List.copyOf(clientTypes);
    }

    public boolean hasInstrumentedClient() {
        return !clientTypes.isEmpty();
    }

    /**
     * Records one outbound call, truncating oversized URIs/header values and evicting the oldest entry
     * when full. Values are stored raw (unmasked); masking is applied per-name at report time so it can
     * honor the live exposure policy (see the class-level docs).
     */
    public void record(
            String method,
            String uri,
            String host,
            String path,
            Integer status,
            long durationMillis,
            boolean success,
            String errorMessage,
            String clientType,
            Map<String, String> headers,
            String thread) {
        if (!enabled || idleSuspended || !recording.get()) {
            return;
        }
        CapturedCall entry = new CapturedCall(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                method,
                truncate(uri, maxUriLength),
                host,
                truncate(path, maxUriLength),
                status,
                Math.max(0, durationMillis),
                success,
                errorMessage,
                clientType,
                captureHeaders ? truncateHeaderValues(headers) : Map.of(),
                thread,
                resolveTraceId(),
                captureCallSite ? currentCallSite() : null);
        synchronized (lock) {
            buffer.addLast(entry);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
                evicted.incrementAndGet();
            }
        }
        totalCaptured.incrementAndGet();
        notifyListeners();
    }

    /** Returns the retained calls, most recent first. */
    public List<CapturedCall> recent() {
        synchronized (lock) {
            List<CapturedCall> snapshot = new ArrayList<>(buffer);
            java.util.Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return evicted.get();
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
        notifyListeners();
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        clear();
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Registers a listener invoked (with no payload) whenever the trace changes, i.e. on a recorded call, a
     * {@link #clear()}, or a recording pause/resume. Returns a handle that removes the listener when run.
     * Listener failures are isolated so they cannot disrupt the outbound call.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt an outbound call.
            }
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public RestClientTraceStatsDto stats() {
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long failed = 0;
        long errorStatus = 0;
        long gets = 0;
        long posts = 0;
        long puts = 0;
        long deletes = 0;
        long others = 0;
        List<CapturedCall> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        for (CapturedCall entry : snapshot) {
            total++;
            totalDuration += entry.durationMillis();
            maxDuration = Math.max(maxDuration, entry.durationMillis());
            if (isSlow(entry.durationMillis())) {
                slow++;
            }
            if (!entry.success()) {
                failed++;
            }
            if (entry.status() != null && entry.status() >= 400) {
                errorStatus++;
            }
            String method = entry.method() == null ? "" : entry.method().toUpperCase(Locale.ROOT);
            switch (method) {
                case "GET" -> gets++;
                case "POST" -> posts++;
                case "PUT" -> puts++;
                case "DELETE" -> deletes++;
                default -> others++;
            }
        }
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new RestClientTraceStatsDto(
                total,
                totalDuration,
                maxDuration,
                avg,
                slow,
                failed,
                errorStatus,
                gets,
                posts,
                puts,
                deletes,
                others,
                evicted.get());
    }

    /**
     * Groups the retained calls by method/host/normalized path (see {@link RestClientTraceGrouping}),
     * ordered by call count descending and bounded to {@link #TOP_CALLS_LIMIT} groups. Grouping keys
     * (method/host/path) are never masked, so {@code maskSecrets}/{@code exposure} only affect the
     * per-entry display values folded into each group's sample call sites.
     */
    public List<RestClientTraceGroupDto> topCalls(boolean maskSecrets, ValueExposure exposure) {
        List<RestClientTraceEntryDto> entries = recent().stream()
                .map(entry -> toDto(entry, maskSecrets, exposure))
                .toList();
        return RestClientTraceGrouping.group(entries, chattyCallThreshold).stream()
                .limit(TOP_CALLS_LIMIT)
                .toList();
    }

    /**
     * Assembles the immutable {@link RestClientTraceReport} the panel and Live Activity render, displaying
     * query parameter and header values per the live {@code maskSecrets}/{@code exposure} policy (see the
     * class-level docs).
     */
    public RestClientTraceReport report(boolean maskSecrets, ValueExposure exposure) {
        List<RestClientTraceEntryDto> entries = recent().stream()
                .map(entry -> toDto(entry, maskSecrets, exposure))
                .toList();
        return new RestClientTraceReport(
                true,
                null,
                isRecording(),
                isCaptureHeaders(),
                getMaxEntries(),
                totalCaptured(),
                getSlowCallThresholdMillis(),
                clientTypes(),
                stats(),
                entries,
                topCalls(maskSecrets, exposure),
                warnings());
    }

    private List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new calls.");
        }
        if (isCaptureHeaders()) {
            warnings.add("Request headers are captured. Sensitive values are masked by name when "
                    + "displayed, but review bootui.rest-client-trace.capture-headers if this is a shared "
                    + "environment.");
        }
        if (evicted() > 0) {
            warnings.add("Older calls were dropped; the buffer keeps the most recent " + getMaxEntries() + ".");
        }
        return warnings;
    }

    private RestClientTraceEntryDto toDto(CapturedCall entry, boolean maskSecrets, ValueExposure exposure) {
        return new RestClientTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.method(),
                displayUri(entry.uri(), maskSecrets, exposure),
                entry.host(),
                entry.path(),
                entry.status(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                isSlow(entry.durationMillis()),
                entry.clientType(),
                displayHeaders(entry.requestHeaders(), maskSecrets, exposure),
                entry.traceId(),
                entry.thread(),
                entry.callSite());
    }

    /**
     * Displays the (already length-bounded) stored URI, dropping the whole query string under {@link
     * ValueExposure#METADATA_ONLY} and otherwise masking each query parameter value by name (mirrors
     * {@code HttpExchangesService#displayUri}). Malformed query strings (no {@code =}) are passed through
     * unmodified rather than dropped. The base URI and parameter names are never masked.
     */
    private String displayUri(String uri, boolean maskSecrets, ValueExposure exposure) {
        if (uri == null) {
            return null;
        }
        int queryIndex = uri.indexOf('?');
        if (queryIndex < 0) {
            return uri;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return uri.substring(0, queryIndex);
        }
        if (queryIndex == uri.length() - 1) {
            return uri;
        }
        String base = uri.substring(0, queryIndex);
        String[] pairs = uri.substring(queryIndex + 1).split("&");
        StringBuilder display = new StringBuilder(base).append('?');
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                display.append('&');
            }
            display.append(displayQueryPart(pairs[i], maskSecrets, exposure));
        }
        return display.toString();
    }

    private String displayQueryPart(String pair, boolean maskSecrets, ValueExposure exposure) {
        int eq = pair.indexOf('=');
        if (eq < 0) {
            return pair;
        }
        String name = pair.substring(0, eq);
        String value = displayValue(name, pair.substring(eq + 1), maskSecrets, exposure);
        return name + '=' + value;
    }

    /**
     * Displays the (already length-bounded) stored request headers, masking values by name per {@code
     * maskSecrets}/{@code exposure} (mirrors {@code HttpExchangesService#headers}). Header names are never
     * masked.
     */
    private Map<String, String> displayHeaders(
            Map<String, String> rawHeaders, boolean maskSecrets, ValueExposure exposure) {
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            return Map.of();
        }
        Map<String, String> display = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : rawHeaders.entrySet()) {
            display.put(header.getKey(), displayValue(header.getKey(), header.getValue(), maskSecrets, exposure));
        }
        return display;
    }

    /**
     * Applies the live exposure policy to a single named value: {@link ValueExposure#METADATA_ONLY} hides
     * it entirely (an empty string, since the enclosing maps/records here don't allow {@code null}
     * values); otherwise a secret-looking name is masked via {@link SecretMasker} unless {@code exposure}
     * is {@link ValueExposure#FULL}.
     */
    private String displayValue(String name, String value, boolean maskSecrets, ValueExposure exposure) {
        if (value == null) {
            return null;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return "";
        }
        if (shouldMask(name, maskSecrets) && exposure != ValueExposure.FULL) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }

    private boolean shouldMask(String name, boolean maskSecrets) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return maskSecrets && (masker.isSecret(name) || SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT)));
    }

    /** Bounds each stored header value's length without masking; masking happens at display time. */
    private Map<String, String> truncateHeaderValues(Map<String, String> rawHeaders) {
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            return Map.of();
        }
        Map<String, String> truncated = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : rawHeaders.entrySet()) {
            truncated.put(header.getKey(), truncate(header.getValue(), maxHeaderValueLength));
        }
        return truncated;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max) + "…";
    }

    /**
     * The trace id to stamp on the next captured call, taken from the configured {@link TraceIdProvider}
     * and fully guarded so an outbound call is never disrupted by a missing or misbehaving provider.
     * Returns {@code null} (no correlation) when blank or on any failure.
     */
    private String resolveTraceId() {
        try {
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Default trace-id source: the SLF4J MDC where Micrometer Tracing publishes it (the {@code traceId}
     * correlation key). Returns {@code null} when no tracer is active or the key is absent, in which case
     * downstream correlation falls back to its time-window heuristic. The lookup is fully guarded so an
     * outbound call is never disrupted by a missing or misbehaving MDC.
     */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Bound on how many stack frames are inspected before giving up on finding an application frame. */
    private static final int MAX_CALL_SITE_FRAMES = 128;

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    /**
     * Best-effort location of the first application stack frame above the HTTP client call — i.e. the
     * first frame that isn't the JDK, Spring's own client plumbing, or BootUI's own instrumentation (see
     * {@link StackFramePrefixes}) — formatted the same way as {@code SqlTraceRecorder}'s call site: {@code
     * ClassName.methodName(File.java:42)}. Walks at most {@link #MAX_CALL_SITE_FRAMES} frames of the
     * current thread's stack, short-circuiting at the first match. Fully guarded so a stack-walking
     * failure can never disrupt the outbound call; returns {@code null} when no application frame is found
     * within the bound, or on any failure.
     */
    private static String currentCallSite() {
        try {
            return STACK_WALKER.walk(RestClientTraceRecorder::selectCallSite);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Pure frame-selection logic factored out of {@link #currentCallSite()} so it can be unit-tested with a
     * synthetic frame stream. Package-private for tests.
     */
    static String selectCallSite(Stream<StackWalker.StackFrame> frames) {
        return frames.limit(MAX_CALL_SITE_FRAMES)
                .filter(frame -> !StackFramePrefixes.isFrameworkClass(frame.getClassName()))
                .findFirst()
                .map(RestClientTraceRecorder::formatFrame)
                .orElse(null);
    }

    private static String formatFrame(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        String position = file == null
                ? "Unknown Source"
                : (frame.getLineNumber() >= 0 ? file + ":" + frame.getLineNumber() : file);
        return frame.getClassName() + "." + frame.getMethodName() + "(" + position + ")";
    }
}
