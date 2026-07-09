package io.github.jdubois.bootui.engine.exceptions;

import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Framework-neutral, in-memory, bounded store of exceptions thrown by the host application, grouped by a
 * stable fingerprint so repeated failures collapse into a single entry with an occurrence count. Shared
 * by the Spring Boot and Quarkus adapters: Spring feeds it from a Logback appender (logged throwables)
 * and an MVC {@code HandlerExceptionResolver} (web request exceptions, with request context); Quarkus
 * feeds it from a {@code java.util.logging} handler and a Vert.x failure handler. Both serve the
 * identical wire on top, so the single shared Vue panel renders the same on every platform.
 *
 * <p>To keep one logical failure from being counted twice when more than one feeder sees it — handled
 * <em>and</em> logged, or wrapped by the framework before logging — the store deduplicates by
 * {@link Throwable} identity <em>across the whole cause chain</em>, using a weakly-referenced set so the
 * bookkeeping cannot itself pin exceptions in memory. A throwable whose chain already contains a
 * recorded instance (in either direction) is treated as a duplicate; otherwise its entire chain is
 * marked seen. A caller-supplied {@code ignore} predicate drops adapter-specific noise (e.g. Spring
 * client-disconnect exceptions) without coupling the engine to any framework type.</p>
 *
 * <p>All retained data lives only in memory, is bounded, and is reset on application restart or via
 * {@link #clear()}.</p>
 *
 * <p><strong>Triage lifecycle.</strong> Every group carries a {@link Status}, defaulting to
 * {@link Status#OPEN} on creation. A developer moves a group to {@link Status#ACKNOWLEDGED} (seen,
 * still investigating) or {@link Status#RESOLVED} (believed fixed) via {@link #setStatus}. Only a
 * {@code RESOLVED} group auto-reopens: if {@link #capture} sees another occurrence of a fingerprint
 * whose group is currently {@code RESOLVED}, the group flips back to {@code OPEN} and its
 * {@code regressionCount} is incremented — a Sentry-style regression signal that a fix didn't hold.
 * An {@code ACKNOWLEDGED} group deliberately does <em>not</em> auto-transition on new occurrences; it
 * keeps accumulating {@code count}/{@code lastSeen} only, since the developer already knows about it
 * and has not claimed it is fixed, so silently resetting their triage state on every duplicate
 * occurrence would be noisy and would defeat the point of acknowledging it. {@code regressionCount} is
 * a lifetime counter: manual {@link #setStatus} calls never change it, so it keeps answering "has this
 * exact failure signature come back before?" across any number of later manual resolves.</p>
 */
public final class ExceptionStore {

    /** Number of leading frames that contribute to a group's fingerprint. */
    private static final int FINGERPRINT_FRAMES = 5;

    /** Maximum depth walked through an exception's cause chain. */
    private static final int MAX_CAUSE_DEPTH = 8;

    /** Upper bound on a retained raw message, before display masking/truncation. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final int maxGroups;
    private final int maxOccurrencesPerGroup;
    private final int maxStackFrames;
    private final Predicate<Throwable> ignore;

    private final Object lock = new Object();
    private final Map<String, Group> groups = new HashMap<>();
    private final Set<Throwable> seen = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile List<String> applicationPackages = List.of();
    private volatile SpanEnricher spanEnricher = SpanEnricher.NO_OP;

    public ExceptionStore(int maxGroups, int maxOccurrencesPerGroup, int maxStackFrames) {
        this(maxGroups, maxOccurrencesPerGroup, maxStackFrames, throwable -> false);
    }

    /**
     * @param ignore caller predicate for adapter-specific noise to drop entirely; used by the Spring
     *     adapter to skip client-disconnect exceptions. A {@code null} predicate ignores nothing.
     */
    public ExceptionStore(int maxGroups, int maxOccurrencesPerGroup, int maxStackFrames, Predicate<Throwable> ignore) {
        this.maxGroups = Math.max(1, maxGroups);
        this.maxOccurrencesPerGroup = Math.max(1, maxOccurrencesPerGroup);
        this.maxStackFrames = Math.max(1, maxStackFrames);
        this.ignore = ignore == null ? throwable -> false : ignore;
    }

    public void setApplicationPackages(List<String> packages) {
        this.applicationPackages = packages == null ? List.of() : List.copyOf(packages);
    }

    /**
     * Installs the {@link SpanEnricher} used to stamp {@code bootui.exception.*} attributes on the active
     * request span as exceptions are captured. Defaults to {@link SpanEnricher#NO_OP}; each adapter installs
     * the OpenTelemetry-backed enricher only when OpenTelemetry tracing is present. Passing {@code null}
     * restores the no-op.
     */
    public void setSpanEnricher(SpanEnricher spanEnricher) {
        this.spanEnricher = spanEnricher == null ? SpanEnricher.NO_OP : spanEnricher;
    }

    /**
     * Records a thrown exception. No-ops if any {@link Throwable} in this throwable's cause chain was
     * already recorded, so a failure observed by more than one feeder — and a framework wrapper around
     * an already-seen cause — counts once. The whole chain is then marked seen so a later wrapper of the
     * same cause is also recognized as a duplicate.
     */
    public void record(Throwable throwable, String thread, String method, String path, String handler, String source) {
        record(throwable, thread, method, path, handler, source, null);
    }

    /**
     * Records a thrown exception, stamping the distributed-trace id of the request in flight so the Live
     * Activity timeline can nest the exception under its owning request. The {@code traceId} is supplied by
     * the adapter capture point; pass {@code null} when no trace context is available or applicable — the
     * behavior of the {@linkplain #record(Throwable, String, String, String, String, String) six-argument
     * overload}, which the Spring <strong>servlet</strong> (MVC) handler-exception capture path always uses
     * (that adapter correlates by serving thread instead — see {@code ExceptionGroupDto.lastTraceId}).
     * Spring <strong>WebFlux</strong>'s handler-exception capture and Quarkus's handler and log-based
     * capture all supply a real trace id via their own {@code TraceIdProvider}; Spring's Logback-based
     * log-appender capture path (shared by both the servlet and WebFlux adapters) always passes
     * {@code null}, since it has no reliable request-scoped context to read from an appender callback.
     */
    public void record(
            Throwable throwable,
            String thread,
            String method,
            String path,
            String handler,
            String source,
            String traceId) {
        if (throwable == null || ignore.test(throwable) || !markSeen(throwable)) {
            return;
        }
        List<Frame> frames = buildFrames(throwable.getStackTrace());
        List<Cause> causes = buildCauses(throwable);
        capture(
                throwable.getClass().getName(),
                throwable.getMessage(),
                frames,
                causes,
                thread,
                method,
                path,
                handler,
                source,
                traceId);
    }

    /**
     * Marks every throwable in the cause chain seen. Returns {@code true} if this is the first feeder to
     * observe the chain, {@code false} if any link was already recorded (a duplicate from another source).
     */
    private boolean markSeen(Throwable throwable) {
        boolean fresh = true;
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (!seen.add(current)) {
                fresh = false;
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return fresh;
    }

    private void capture(
            String exceptionClassName,
            String rawMessage,
            List<Frame> frames,
            List<Cause> causes,
            String thread,
            String method,
            String path,
            String handler,
            String source,
            String traceId) {
        String className = exceptionClassName == null ? "java.lang.Throwable" : exceptionClassName;
        List<Frame> safeFrames = frames == null ? List.of() : frames;
        List<Cause> safeCauses = causes == null ? List.of() : causes;
        String message = truncate(rawMessage);
        String fingerprint = fingerprint(className, safeFrames);
        String location = location(safeFrames);
        boolean applicationException = safeFrames.stream().anyMatch(Frame::applicationFrame);
        long now = System.currentTimeMillis();
        Occurrence occurrence = new Occurrence(now, thread, method, path, handler, source, traceId);

        synchronized (lock) {
            Group group = groups.get(fingerprint);
            if (group == null) {
                group = new Group(fingerprint, className, now);
                groups.put(fingerprint, group);
                evictIfNeeded();
            } else if (group.status == Status.RESOLVED) {
                // Regression: a group believed fixed just fired again. Reopen it and mark the
                // regression, but leave ACKNOWLEDGED groups alone (see class Javadoc).
                group.status = Status.OPEN;
                group.regressionCount++;
            }
            group.message = message;
            group.frames = safeFrames;
            group.causes = safeCauses;
            group.location = location;
            group.applicationException = applicationException;
            group.lastSeen = now;
            group.count++;
            group.addOccurrence(occurrence, maxOccurrencesPerGroup);
        }
        notifyListeners();
        spanEnricher.onException(className);
    }

    public List<GroupSummary> groups() {
        synchronized (lock) {
            List<GroupSummary> summaries = new ArrayList<>(groups.size());
            for (Group group : groups.values()) {
                summaries.add(group.summary());
            }
            summaries.sort(Comparator.comparingLong(GroupSummary::lastSeen).reversed());
            return summaries;
        }
    }

    public long totalExceptions() {
        synchronized (lock) {
            long total = 0L;
            for (Group group : groups.values()) {
                total += group.count;
            }
            return total;
        }
    }

    public GroupDetail find(String fingerprint) {
        synchronized (lock) {
            Group group = groups.get(fingerprint);
            return group == null ? null : group.detail();
        }
    }

    /**
     * Manually moves a group to the given {@link Status} (e.g. acknowledging or resolving it from the
     * UI). Unlike the automatic regression reopen in {@link #capture}, this never changes
     * {@code regressionCount} — it is a lifetime counter of automatic reopens, not of manual triage
     * actions. Returns the updated {@link GroupSummary}, or {@code null} if {@code fingerprint} is
     * unknown.
     */
    public GroupSummary setStatus(String fingerprint, Status status) {
        Objects.requireNonNull(status, "status");
        GroupSummary updated;
        synchronized (lock) {
            Group group = groups.get(fingerprint);
            if (group == null) {
                return null;
            }
            group.status = status;
            updated = group.summary();
        }
        notifyListeners();
        return updated;
    }

    public void clear() {
        synchronized (lock) {
            groups.clear();
        }
        seen.clear();
        notifyListeners();
    }

    /**
     * Registers a listener invoked (with no payload) whenever the store changes, i.e. on a recorded
     * exception or a {@link #clear()}. Returns a handle that removes the listener when run. Listener
     * failures are isolated so one bad subscriber cannot break exception capture.
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
                // A misbehaving stream subscriber must never disrupt exception capture.
            }
        }
    }

    public int maxGroups() {
        return maxGroups;
    }

    private void evictIfNeeded() {
        if (groups.size() <= maxGroups) {
            return;
        }
        String oldest = null;
        long oldestSeen = Long.MAX_VALUE;
        for (Group group : groups.values()) {
            if (group.lastSeen < oldestSeen) {
                oldestSeen = group.lastSeen;
                oldest = group.fingerprint;
            }
        }
        if (oldest != null) {
            groups.remove(oldest);
        }
    }

    private List<Frame> buildFrames(StackTraceElement[] trace) {
        if (trace == null || trace.length == 0) {
            return List.of();
        }
        int limit = Math.min(trace.length, maxStackFrames);
        List<Frame> frames = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            frames.add(toFrame(trace[i]));
        }
        return frames;
    }

    private Frame toFrame(StackTraceElement element) {
        return new Frame(
                element.getClassName(),
                element.getMethodName(),
                element.getFileName(),
                element.getLineNumber(),
                isApplicationFrame(element.getClassName()));
    }

    private List<Cause> buildCauses(Throwable throwable) {
        List<Cause> causes = new ArrayList<>();
        Throwable enclosing = throwable;
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && cause != enclosing && depth < MAX_CAUSE_DEPTH) {
            causes.add(toCause(cause, enclosing));
            enclosing = cause;
            cause = cause.getCause();
            depth++;
        }
        return causes;
    }

    private Cause toCause(Throwable cause, Throwable enclosing) {
        StackTraceElement[] causeTrace = cause.getStackTrace();
        StackTraceElement[] enclosingTrace = enclosing.getStackTrace();
        int m = causeTrace.length - 1;
        int n = enclosingTrace.length - 1;
        while (m >= 0 && n >= 0 && causeTrace[m].equals(enclosingTrace[n])) {
            m--;
            n--;
        }
        int commonFrames = causeTrace.length - 1 - m;
        int uniqueCount = Math.min(m + 1, maxStackFrames);
        List<Frame> frames = new ArrayList<>(Math.max(0, uniqueCount));
        for (int i = 0; i < uniqueCount; i++) {
            frames.add(toFrame(causeTrace[i]));
        }
        return new Cause(cause.getClass().getName(), truncate(cause.getMessage()), frames, Math.max(0, commonFrames));
    }

    private boolean isApplicationFrame(String className) {
        if (className == null) {
            return false;
        }
        List<String> packages = applicationPackages;
        if (!packages.isEmpty()) {
            for (String prefix : packages) {
                if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                    return true;
                }
            }
            return false;
        }
        return !StackFramePrefixes.isFrameworkClass(className);
    }

    private static String location(List<Frame> frames) {
        Frame chosen = null;
        for (Frame frame : frames) {
            if (frame.applicationFrame()) {
                chosen = frame;
                break;
            }
        }
        if (chosen == null && !frames.isEmpty()) {
            chosen = frames.get(0);
        }
        if (chosen == null) {
            return null;
        }
        String file = chosen.fileName();
        String position =
                file == null ? "Unknown Source" : (chosen.lineNumber() >= 0 ? file + ":" + chosen.lineNumber() : file);
        return chosen.declaringClass() + "." + chosen.methodName() + "(" + position + ")";
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private static String fingerprint(String className, List<Frame> frames) {
        StringBuilder builder = new StringBuilder(className);
        int count = Math.min(frames.size(), FINGERPRINT_FRAMES);
        for (int i = 0; i < count; i++) {
            Frame frame = frames.get(i);
            builder.append('\n')
                    .append(frame.declaringClass())
                    .append('#')
                    .append(frame.methodName())
                    .append(':')
                    .append(frame.lineNumber());
        }
        return sha256Hex(builder.toString()).substring(0, 16);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    private static final class Group {

        private final String fingerprint;
        private final String exceptionClassName;
        private final long firstSeen;
        private final Deque<Occurrence> occurrences = new ArrayDeque<>();

        private String message;
        private String location;
        private boolean applicationException;
        private long lastSeen;
        private long count;
        private List<Frame> frames = List.of();
        private List<Cause> causes = List.of();
        private Status status = Status.OPEN;
        private long regressionCount;

        private Group(String fingerprint, String exceptionClassName, long firstSeen) {
            this.fingerprint = fingerprint;
            this.exceptionClassName = exceptionClassName;
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }

        private void addOccurrence(Occurrence occurrence, int max) {
            occurrences.addFirst(occurrence);
            while (occurrences.size() > max) {
                occurrences.removeLast();
            }
        }

        private GroupSummary summary() {
            Occurrence last = occurrences.peekFirst();
            return new GroupSummary(
                    fingerprint,
                    exceptionClassName,
                    message,
                    count,
                    firstSeen,
                    lastSeen,
                    location,
                    applicationException,
                    last,
                    status,
                    regressionCount);
        }

        private GroupDetail detail() {
            return new GroupDetail(summary(), List.copyOf(frames), List.copyOf(causes), new ArrayList<>(occurrences));
        }
    }

    public record Frame(
            String declaringClass, String methodName, String fileName, int lineNumber, boolean applicationFrame) {}

    public record Cause(String exceptionClassName, String message, List<Frame> frames, int commonFrames) {}

    public record Occurrence(
            long timestamp,
            String thread,
            String requestMethod,
            String requestPath,
            String handler,
            String source,
            String traceId) {}

    public record GroupSummary(
            String fingerprint,
            String exceptionClassName,
            String message,
            long count,
            long firstSeen,
            long lastSeen,
            String location,
            boolean applicationException,
            Occurrence last,
            Status status,
            long regressionCount) {}

    public record GroupDetail(
            GroupSummary summary, List<Frame> frames, List<Cause> causes, List<Occurrence> occurrences) {}

    /**
     * Triage status of a group. See the class Javadoc for the full lifecycle and the regression
     * auto-reopen rule.
     */
    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED
    }
}
