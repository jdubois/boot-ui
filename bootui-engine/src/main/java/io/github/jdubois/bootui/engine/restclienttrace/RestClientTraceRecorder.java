package io.github.jdubois.bootui.engine.restclienttrace;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceStatsDto;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.engine.support.DetailText;
import io.github.jdubois.bootui.engine.support.SensitiveNames;
import io.github.jdubois.bootui.engine.support.UriMasking;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.ArrayList;
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
 * <p>Mirrors {@code SqlTraceRecorder}'s shape: thread-safe, capped at {@code maxEntries}, and can be
 * paused/resumed at runtime via {@link #setRecording(boolean)} without removing the client instrumentation.
 * Retention is failure-preserving ({@link TieredCaptureBuffer}): a bounded share of the capacity is reserved
 * for failed calls, error responses ({@code 4xx}/{@code 5xx}), and slow calls, so routine calls are evicted
 * first. The recorder also tracks which client types were actually instrumented, so
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
 *
 * <p>Two things are sanitized before storage rather than at display time, because no exposure setting ever
 * reveals them: URI authority user-info credentials, and the client {@code errorMessage}, which is flattened,
 * credential-redacted, and length-bounded since a transport exception can quote a whole request URL.</p>
 */
public final class RestClientTraceRecorder implements IdleReclaimable, RuntimeEventPublisher {

    static final int TOP_CALLS_LIMIT = 20;

    /**
     * A single immutable captured outbound HTTP call. Query parameter values in {@code uri} and header
     * values in {@code requestHeaders} are truncated but otherwise raw; masking is applied at display time
     * (see the class-level docs), not when this record is created. URI user-info credentials and
     * {@code errorMessage} are the exceptions: both are sanitized before they ever reach this record, since
     * neither is a value the exposure policy can ever widen.
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
            String callSite,
            String requestId,
            String executionId,
            String threadKind) {
        /** Without the thread kind. */
        public CapturedCall(
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
                String callSite,
                String requestId,
                String executionId) {
            this(
                    id,
                    timestamp,
                    method,
                    uri,
                    host,
                    path,
                    status,
                    durationMillis,
                    success,
                    errorMessage,
                    clientType,
                    requestHeaders,
                    thread,
                    traceId,
                    callSite,
                    requestId,
                    executionId,
                    null);
        }

        /** Without the thread kind. */
        public CapturedCall(
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
                String callSite,
                String requestId) {
            this(
                    id,
                    timestamp,
                    method,
                    uri,
                    host,
                    path,
                    status,
                    durationMillis,
                    success,
                    errorMessage,
                    clientType,
                    requestHeaders,
                    thread,
                    traceId,
                    callSite,
                    requestId,
                    null);
        }

        public CapturedCall {
            requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        }

        /** Without BootUI's request identity. */
        public CapturedCall(
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
            this(
                    id,
                    timestamp,
                    method,
                    uri,
                    host,
                    path,
                    status,
                    durationMillis,
                    success,
                    errorMessage,
                    clientType,
                    requestHeaders,
                    thread,
                    traceId,
                    callSite,
                    null);
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

    private final TieredCaptureBuffer<CapturedCall> buffer;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final Set<String> clientTypes = new ConcurrentSkipListSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile TraceIdProvider traceIdProvider = RestClientTraceRecorder::mdcTraceId;
    private final CorrelationSource correlation = new CorrelationSource();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
    private final ThreadKinds threadKinds = new ThreadKinds();

    /** A recorder reserving the default share of its buffer for failed, error-response, and slow calls. */
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
        this(
                enabled,
                recording,
                captureHeaders,
                captureCallSite,
                maxEntries,
                slowCallThresholdMillis,
                maxUriLength,
                maxHeaderValueLength,
                chattyCallThreshold,
                TieredCaptureBuffer.DEFAULT_RESERVED_SHARE_PERCENT);
    }

    /**
     * @param reservedSharePercent share of {@code maxEntries} reserved for failed, error-response, and slow calls
     *     ({@code bootui.rest-client-trace.reserved-share-percent}); {@code 0} evicts strictly oldest first
     */
    public RestClientTraceRecorder(
            boolean enabled,
            boolean recording,
            boolean captureHeaders,
            boolean captureCallSite,
            int maxEntries,
            long slowCallThresholdMillis,
            int maxUriLength,
            int maxHeaderValueLength,
            int chattyCallThreshold,
            int reservedSharePercent) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.captureHeaders = captureHeaders;
        this.captureCallSite = captureCallSite;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowCallThresholdMillis = Math.max(0, slowCallThresholdMillis);
        this.maxUriLength = Math.max(16, maxUriLength);
        this.maxHeaderValueLength = Math.max(8, maxHeaderValueLength);
        this.chattyCallThreshold = Math.max(2, chattyCallThreshold);
        this.buffer = new TieredCaptureBuffer<>(this.maxEntries, reservedSharePercent);
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

    /**
     * Replaces the source of the request id stamped on each capture ({@code docs/PLAN-v2.md} §5.1). Defaults to the
     * thread's correlation scope; the Quarkus adapter installs one that reads the request's Vert.x context. Passing
     * {@code null} restores the default.
     */
    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded call right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public void setCorrelationContextProvider(CorrelationContextProvider correlationProvider) {
        correlation.set(correlationProvider);
    }

    /**
     * Replaces the classifier of the thread kind stamped on each capture ({@code docs/PLAN-v2.md} §5.1): each adapter
     * installs one that knows its own threads. Defaults to classifying virtual threads only; {@code null} restores it.
     */
    public void setThreadKindClassifier(ThreadKindClassifier classifier) {
        threadKinds.set(classifier);
    }

    /** The kind of the calling thread, for a client that records a call later, on another thread. */
    public ThreadKind currentThreadKind() {
        return threadKinds.current();
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

    /** Calls of {@link #getMaxEntries()} reserved for failed, error-response, and slow calls. */
    public int getReservedCapacity() {
        return buffer.reservedCapacity();
    }

    /**
     * Whether a call belongs in the reserved share: the client threw, the server answered {@code 4xx}/{@code 5xx}
     * (the same "error response" the panel counts), or the call reached the slow-call threshold. The one rule both
     * this recorder and the Live Activity persistence capture apply.
     */
    public static boolean isFailedOrSlow(boolean success, Integer status, boolean slow) {
        return !success || (status != null && status >= 400) || slow;
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
     * Records one outbound call, truncating oversized URIs/header values and evicting per the
     * failure-preserving policy when full. Query and header values are stored raw (unmasked) so masking can honor the live exposure
     * policy at report time (see the class-level docs); URI user-info credentials and the client error
     * message are sanitized here instead, because no exposure setting ever reveals them.
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
        if (!shouldRecord()) {
            return;
        }
        append(
                method,
                uri,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                clientType,
                headers,
                thread,
                currentTraceId(),
                correlation.current(),
                null);
    }

    /**
     * Records one outbound call with a trace id captured explicitly at the interception boundary.
     * Reactive adapters use this overload when the active context is available in the request filter but
     * no longer attached when the response callback runs.
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
            String thread,
            String traceId) {
        record(
                method,
                uri,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                clientType,
                headers,
                thread,
                traceId,
                correlation.current());
    }

    /**
     * Records one outbound call with the trace id and BootUI request id captured explicitly at the interception
     * boundary, for clients whose response callback may run where the request's context is no longer current.
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
            String thread,
            String traceId,
            String requestId) {
        record(
                method,
                uri,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                clientType,
                headers,
                thread,
                traceId,
                CorrelationContext.forRequest(normalizeTraceId(requestId)));
    }

    /**
     * Records one outbound call with the trace id and BootUI correlation captured explicitly at the interception
     * boundary, classifying the recording thread's kind.
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
            String thread,
            String traceId,
            CorrelationContext caller) {
        record(
                method,
                uri,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                clientType,
                headers,
                thread,
                traceId,
                caller,
                null);
    }

    /**
     * Records one outbound call with the trace id and BootUI correlation captured explicitly at the interception
     * boundary, for clients whose response callback may run where the caller's context is no longer current.
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
            String thread,
            String traceId,
            CorrelationContext caller,
            ThreadKind threadKind) {
        if (!shouldRecord()) {
            return;
        }
        append(
                method,
                uri,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                clientType,
                headers,
                thread,
                normalizeTraceId(traceId),
                caller == null ? CorrelationContext.NONE : caller,
                threadKind);
    }

    /**
     * Whether a call is worth observing: the panel captures now, or, while it is paused or idle-suspended, the runtime
     * journal still records outbound calls ({@code docs/PLAN-v2.md} §5.2).
     */
    private boolean shouldRecord() {
        return enabled && (capturesForPanel() || journal.records(JournalSource.REST_CLIENT));
    }

    /** Whether the REST Client Trace panel captures calls now: enabled, recording, and not suspended while idle. */
    private boolean capturesForPanel() {
        return enabled && !idleSuspended && recording.get();
    }

    private void append(
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
            String thread,
            String traceId,
            CorrelationContext caller,
            ThreadKind threadKind) {
        ApplicationFrames frames = captureCallSite ? ApplicationFrames.capture() : null;
        long timestamp = System.currentTimeMillis();
        long duration = Math.max(0, durationMillis);
        String maskedUri = truncate(UriMasking.maskUserInfo(uri), maxUriLength);
        String truncatedPath = truncate(path, maxUriLength);
        ThreadKind kind = threadKind == null ? threadKinds.current() : threadKind;
        boolean failedOrSlow = isFailedOrSlow(success, status, isSlow(duration));
        boolean panel = capturesForPanel();
        if (panel) {
            CapturedCall entry = new CapturedCall(
                    sequence.incrementAndGet(),
                    timestamp,
                    method,
                    maskedUri,
                    host,
                    truncatedPath,
                    status,
                    duration,
                    success,
                    sanitizeErrorMessage(errorMessage),
                    clientType,
                    captureHeaders ? truncateHeaderValues(headers) : Map.of(),
                    thread,
                    traceId,
                    frames == null ? null : frames.callSite(),
                    caller.requestId(),
                    caller.executionId(),
                    kind.name());
            buffer.add(entry, failedOrSlow);
        }
        journal.offer(new RuntimeEvent(
                JournalSource.REST_CLIENT,
                timestamp,
                duration * 1_000_000,
                caller.requestId(),
                caller.executionId(),
                traceId,
                caller.spanId(),
                thread,
                kind,
                failedOrSlow,
                new RestClientPayload(
                        method,
                        RestClientAuthority.of(host, maskedUri),
                        truncatedPath,
                        status,
                        clientType,
                        !success,
                        frames,
                        System.nanoTime())));
        if (panel) {
            totalCaptured.incrementAndGet();
            notifyListeners();
        }
    }

    /** Returns the retained calls, most recent first, across both retention tiers. */
    public List<CapturedCall> recent() {
        return new ArrayList<>(buffer.newestFirst());
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return buffer.evicted();
    }

    /** The buffer's retention counts: capacity, reserved share, retained and reserved calls, and evictions. */
    public CaptureRetentionDto retention() {
        return buffer.snapshot().retention(slowCallThresholdMillis);
    }

    public void clear() {
        buffer.clear();
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
        return stats(buffer.snapshot());
    }

    private RestClientTraceStatsDto stats(TieredCaptureBuffer.Snapshot<CapturedCall> snapshot) {
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
        for (CapturedCall entry : snapshot.newestFirst()) {
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
                snapshot.evicted());
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
        return topCalls(entries);
    }

    private List<RestClientTraceGroupDto> topCalls(List<RestClientTraceEntryDto> entries) {
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
        // One snapshot feeds every section, so the entries, statistics, and retention counts always reconcile.
        TieredCaptureBuffer.Snapshot<CapturedCall> snapshot = buffer.snapshot();
        List<RestClientTraceEntryDto> entries = snapshot.newestFirst().stream()
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
                stats(snapshot),
                entries,
                topCalls(entries),
                warnings(snapshot),
                snapshot.retention(slowCallThresholdMillis));
    }

    private List<String> warnings(TieredCaptureBuffer.Snapshot<CapturedCall> snapshot) {
        List<String> warnings = new ArrayList<>();
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new calls.");
        }
        if (isCaptureHeaders()) {
            warnings.add("Request headers are captured. Sensitive values are masked by name when "
                    + "displayed, but review bootui.rest-client-trace.capture-headers if this is a shared "
                    + "environment.");
        }
        if (snapshot.evicted() > 0) {
            String warning = "Older calls were dropped; the buffer keeps up to " + snapshot.capacity() + " calls";
            warnings.add(
                    snapshot.reservedCapacity() == 0
                            ? warning + ", newest first."
                            : warning + ", reserving " + snapshot.reservedCapacity()
                                    + " for the most recent failed, error, or slow ones, so routine calls are "
                                    + "dropped first.");
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
                UriMasking.maskPath(entry.path(), maskSecrets, exposure),
                entry.status(),
                entry.durationMillis(),
                entry.success(),
                displayErrorMessage(entry.errorMessage(), exposure),
                isSlow(entry.durationMillis()),
                entry.clientType(),
                displayHeaders(entry.requestHeaders(), maskSecrets, exposure),
                entry.traceId(),
                entry.thread(),
                entry.callSite(),
                entry.requestId(),
                entry.executionId(),
                entry.threadKind());
    }

    /**
     * Displays the (already user-info-redacted and length-bounded) stored URI, dropping the whole query string
     * under {@link ValueExposure#METADATA_ONLY} and otherwise masking each query or fragment parameter value by
     * name. Shared with the HTTP Exchanges panel through {@link UriMasking}, so inbound and outbound URIs are
     * masked identically. Parameter names and the base URI are never masked; a value-less parameter whose name
     * itself looks sensitive is replaced wholesale.
     */
    private String displayUri(String uri, boolean maskSecrets, ValueExposure exposure) {
        return UriMasking.maskUri(uri, maskSecrets, exposure);
    }

    /**
     * Bounds and sanitizes a client error message before it is buffered: newlines are flattened, URL credentials
     * and sensitive query values echoed by the exception are redacted ({@link CredentialRedaction}), and the
     * result is truncated to {@link DetailText#DEFAULT_MAX_CHARS}. Blank messages become {@code null} so the
     * panel and Live Activity render "no detail" rather than an empty string.
     */
    private static String sanitizeErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String flattened = errorMessage.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (flattened.isEmpty()) {
            return null;
        }
        return truncate(CredentialRedaction.redactMessage(flattened), DetailText.DEFAULT_MAX_CHARS);
    }

    /**
     * Applies the live exposure policy to the stored error message: {@link ValueExposure#METADATA_ONLY} hides it
     * (the failure itself still shows through {@code success}/{@code status}), every other mode keeps the
     * already-sanitized text so a transport failure stays diagnosable.
     */
    private static String displayErrorMessage(String errorMessage, ValueExposure exposure) {
        return exposure == ValueExposure.METADATA_ONLY ? null : errorMessage;
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
        return maskSecrets && SensitiveNames.isSensitive(name);
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

    /** BootUI's request id for the next captured call, or {@code null} when no request owns it; fully guarded. */
    public String currentRequestId() {
        return correlation.requestId();
    }

    /** BootUI's correlation for the next captured call, {@link CorrelationContext#NONE} when nothing owns it. */
    public CorrelationContext currentCorrelation() {
        return correlation.current();
    }

    /**
     * The trace id to stamp on the next captured call, taken from the configured {@link TraceIdProvider}
     * and fully guarded so an outbound call is never disrupted by a missing or misbehaving provider.
     * Returns {@code null} (no correlation) when blank or on any failure.
     */
    public String currentTraceId() {
        try {
            return normalizeTraceId(traceIdProvider.currentTraceId());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeTraceId(String traceId) {
        return traceId == null || traceId.isBlank() ? null : traceId;
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

    /**
     * Pure frame-selection logic factored out of {@link #currentCallSite()} so it can be unit-tested with a
     * synthetic frame stream. Package-private for tests.
     */
    static String selectCallSite(Stream<StackWalker.StackFrame> frames) {
        ApplicationFrames selected = ApplicationFrames.select(frames);
        return selected == null ? null : selected.callSite();
    }
}
