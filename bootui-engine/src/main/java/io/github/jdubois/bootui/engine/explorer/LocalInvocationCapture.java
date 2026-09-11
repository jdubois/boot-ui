package io.github.jdubois.bootui.engine.explorer;

import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryLimits;
import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Synchronous, local-only execution stack. Never touches an OpenTelemetry SDK or global context.
 * The adapter owns one {@link Request} on an eligible sampled HTTP request; the thread stack exists
 * only while its outermost captured call is executing. No throwable or method payload is retained.
 *
 * <p>Completed calls use scope {@value #SCOPE}. Exact identities live in {@code bootui.explorer.bean},
 * {@code bootui.explorer.type}, {@code bootui.explorer.method}, and {@code bootui.explorer.role}.
 * Only {@code exception.type} is recorded for a failed exit. Saturating omissions are available from
 * {@link TelemetryStore.TraceBucket#omittedLocalSpans()}, including store-capacity omissions.
 */
public final class LocalInvocationCapture implements InvocationContextProvider {

    public static final String SCOPE = "bootui.explorer";
    public static final int MAX_CALLS = 100;
    public static final int MAX_DEPTH = 32;

    private final TelemetryStore store;
    private final TelemetrySettings settings;
    private final SelfTelemetryClassifier selfClassifier;
    private final BooleanSupplier enabled;
    private final String serviceName;
    private final ThreadLocal<Request> active = new ThreadLocal<>();

    public LocalInvocationCapture(
            TelemetryStore store,
            TelemetrySettings settings,
            SelfTelemetryClassifier selfClassifier,
            BooleanSupplier enabled,
            String serviceName) {
        this.store = store;
        this.settings = settings;
        this.selfClassifier = selfClassifier;
        this.enabled = enabled;
        this.serviceName = truncate(serviceName == null || serviceName.isBlank() ? "unknown_service" : serviceName);
    }

    /** Called only with genuine HTTP evidence; does not manufacture a trace or activate sampling. */
    public Request request(String traceId, String parentSpanId, boolean sampled, String requestPath) {
        if (!enabled.getAsBoolean()
                || !settings.enabled()
                || !sampled
                || !validId(traceId, 32)
                || !validId(parentSpanId, 16)
                || (settings.excludeSelfSpans() && selfClassifier.isBootUiPath(requestPath))
                || !store.acceptsLocalSpans(traceId)) {
            return null;
        }
        return new Request(traceId, parentSpanId);
    }

    /** Checks all budgets before allocating an invocation or its attributes. Pair with {@link #exit}. */
    public Invocation enter(Request request, String bean, String type, String method, String role) {
        if (request == null
                || request.owner != Thread.currentThread()
                || !enabled.getAsBoolean()
                || !settings.enabled()) {
            return null;
        }
        Request current = active.get();
        if (current != null && current != request) {
            return null;
        }
        if (request.calls >= MAX_CALLS || request.depth >= MAX_DEPTH || request.suppressed > 0) {
            return omit(request);
        }
        TelemetryStore.LocalSpanReservation reservation = store.reserveLocalSpan(request.traceId);
        if (reservation == null) {
            return omit(request);
        }
        request.calls++;
        request.depth++;
        Invocation invocation = new Invocation(request, request.top, reservation, spanId(), bean, type, method, role);
        request.top = invocation;
        active.set(request);
        return invocation;
    }

    private Invocation omit(Request request) {
        store.omitLocalSpan(request.traceId);
        if (active.get() == request) {
            request.suppressed++;
            return Invocation.OMITTED;
        }
        return null;
    }

    /** Always called in finally, even when entry returned null. Never records an exception occurrence. */
    public void exit(Invocation invocation, Throwable failure) {
        if (invocation == null) {
            return;
        }
        if (invocation == Invocation.OMITTED) {
            Request request = active.get();
            if (request != null && request.suppressed > 0) {
                request.suppressed--;
            }
            return;
        }
        Request request = invocation.request;
        if (request.owner != Thread.currentThread() || request.top != invocation) {
            return;
        }
        try {
            Map<String, AttributeValue> attributes = new LinkedHashMap<>();
            attributes.put("bootui.explorer.bean", AttributeValue.ofString(truncate(invocation.bean)));
            attributes.put("bootui.explorer.type", AttributeValue.ofString(truncate(invocation.type)));
            attributes.put("bootui.explorer.method", AttributeValue.ofString(truncate(invocation.method)));
            attributes.put("bootui.explorer.role", AttributeValue.ofString(invocation.role));
            if (failure != null) {
                attributes.put(
                        "exception.type",
                        AttributeValue.ofString(truncate(failure.getClass().getName())));
            }
            long end = invocation.startEpochNanos + Math.max(0, System.nanoTime() - invocation.startNanos);
            NormalizedSpan span = new NormalizedSpan(
                    request.traceId,
                    invocation.spanId,
                    invocation.parent == null ? request.parentSpanId : invocation.parent.spanId,
                    truncate(invocation.bean + "." + invocation.method),
                    "INTERNAL",
                    serviceName,
                    SCOPE,
                    invocation.startEpochNanos,
                    end,
                    failure == null ? "UNSET" : "ERROR",
                    null,
                    Map.copyOf(attributes),
                    List.of());
            if (enabled.getAsBoolean() && settings.enabled()) {
                store.completeLocalSpan(invocation.reservation, span);
            } else {
                store.completeLocalSpan(invocation.reservation, null);
            }
        } finally {
            request.top = invocation.parent;
            request.depth--;
            if (request.top == null) {
                request.suppressed = 0;
                active.remove();
            }
        }
    }

    @Override
    public Context current() {
        Request request = active.get();
        return request == null
                        || request.top == null
                        || request.suppressed > 0
                        || !enabled.getAsBoolean()
                        || !settings.enabled()
                ? EMPTY
                : new Context(request.traceId, request.top.spanId);
    }

    private String truncate(String value) {
        return TelemetryLimits.truncate(value, settings.maxAttributeValueBytes());
    }

    private static String spanId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0);
        return java.util.HexFormat.of().toHexDigits(id);
    }

    private static boolean validId(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        boolean nonzero = false;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
            nonzero |= c != '0';
        }
        return nonzero;
    }

    /** Request-owned budget only: never retained by this capture after the outermost call exits. */
    public static final class Request {
        private final String traceId;
        private final String parentSpanId;
        private final Thread owner = Thread.currentThread();
        private int calls;
        private int depth;
        private long suppressed;
        private Invocation top;

        private Request(String traceId, String parentSpanId) {
            this.traceId = traceId;
            this.parentSpanId = parentSpanId;
        }

        public String traceId() {
            return traceId;
        }
    }

    public static final class Invocation {
        private static final Invocation OMITTED = new Invocation(null, null, null, null, null, null, null, null);
        private final Request request;
        private final Invocation parent;
        private final TelemetryStore.LocalSpanReservation reservation;
        private final String spanId;
        private final String bean;
        private final String type;
        private final String method;
        private final String role;
        private final long startEpochNanos = System.currentTimeMillis() * 1_000_000L;
        private final long startNanos = System.nanoTime();

        private Invocation(
                Request request,
                Invocation parent,
                TelemetryStore.LocalSpanReservation reservation,
                String spanId,
                String bean,
                String type,
                String method,
                String role) {
            this.request = request;
            this.parent = parent;
            this.reservation = reservation;
            this.spanId = spanId;
            this.bean = bean;
            this.type = type;
            this.method = method;
            this.role = role;
        }
    }
}
