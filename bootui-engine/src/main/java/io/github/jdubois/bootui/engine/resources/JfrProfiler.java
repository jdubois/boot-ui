package io.github.jdubois.bootui.engine.resources;

import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * §5.11's opt-in JFR attribution ({@code docs/PLAN-v2.md}, D17): a <b>Profile resources</b> session that the developer
 * starts, bounded by {@code bootui.resources.jfr.max-duration}. While it runs, each request segment is a {@code
 * bootui.ExecutionSegment} event, and JFR samples CPU and allocation; when it ends, each sample is joined to the segment
 * open on its thread at its time, in JFR's own clock, virtual threads included. It never starts on its own or on page
 * load.
 *
 * <p>{@code jdk.CPUTimeSample} is used where it works, on Linux with JDK 25 and later; elsewhere {@code
 * jdk.ExecutionSample}. CPU is reported as samples, never as a measured time. Only one session runs at a time in the
 * JVM, and every JFR class is reached through a guarded nested class, so a runtime without JFR reports it unavailable.</p>
 */
public final class JfrProfiler {

    /** The segment event's name. */
    public static final String SEGMENT_EVENT = "bootui.ExecutionSegment";

    /** The CPU sampler JFR uses where {@code jdk.CPUTimeSample} does not work. */
    public static final String EXECUTION_SAMPLER = "jdk.ExecutionSample";

    /** The CPU-time sampler of JDK 25 and later, on Linux. */
    public static final String CPU_TIME_SAMPLER = "jdk.CPUTimeSample";

    /** How often JFR samples running threads, or each thread's CPU time. */
    static final Duration SAMPLE_PERIOD = Duration.ofMillis(10);

    /** The most requests one session attributes samples to. */
    static final int MAX_REQUESTS = 10_000;

    /** The most distinct frames kept for one request. */
    static final int MAX_FRAMES_PER_REQUEST = 64;

    /** How long {@link #stop()} waits for the session's analysis. */
    static final Duration STOP_WAIT = Duration.ofSeconds(30);

    private static final Logger log = Logger.getLogger(JfrProfiler.class.getName());
    private static final JfrProfiler SHARED = new JfrProfiler(StackFramePrefixes::isFrameworkClass);

    private final Predicate<String> framework;

    private State state = State.IDLE;
    private String reason;
    private String sampler;
    private Instant startedAt;
    private Instant endsAt;
    private Instant finishedAt;
    private Analysis analysis;
    private boolean otherRecording;
    private Object recording;
    private Path file;
    private Thread finisher;
    private boolean stopRequested;

    JfrProfiler(Predicate<String> framework) {
        this.framework = framework;
    }

    /** The JVM's profiler: segments are JVM-wide, so one session runs at a time. */
    public static JfrProfiler shared() {
        return SHARED;
    }

    /** Why this runtime cannot profile, or {@code null} when it can. */
    public static String unavailableReason() {
        try {
            return Jfr.unavailableReason();
        } catch (RuntimeException | LinkageError ex) {
            return "This runtime does not include JDK Flight Recorder (the jdk.jfr module).";
        }
    }

    /** The session's state, and the last session's results once it completed. */
    public synchronized Snapshot snapshot() {
        if (state == State.IDLE || state == State.UNAVAILABLE) {
            String unavailable = unavailableReason();
            if (unavailable != null) {
                return new Snapshot(State.UNAVAILABLE, unavailable, null, null, null, null, Analysis.EMPTY, false);
            }
        }
        return new Snapshot(
                state,
                reason,
                sampler,
                startedAt,
                endsAt,
                finishedAt,
                analysis == null ? Analysis.EMPTY : analysis,
                otherRecording);
    }

    /**
     * Starts a session of {@code duration}, which ends on its own then. Does nothing while one runs, and returns the
     * snapshot either way; a runtime without JFR returns {@link State#UNAVAILABLE}.
     */
    public synchronized Snapshot start(Duration duration) {
        if (state == State.RUNNING) {
            return snapshot();
        }
        String unavailable = unavailableReason();
        if (unavailable != null) {
            return snapshot();
        }
        Path destination = null;
        try {
            destination = Files.createTempFile("bootui-profile-", ".jfr");
            boolean cpuTime = Jfr.cpuTimeSampler();
            recording = Jfr.start(destination, cpuTime);
            JfrSegments.activate(true);
            file = destination;
            sampler = cpuTime ? CPU_TIME_SAMPLER : EXECUTION_SAMPLER;
            otherRecording = Jfr.otherRecordings(recording);
            startedAt = Instant.now();
            endsAt = startedAt.plus(duration);
            finishedAt = null;
            analysis = null;
            reason = null;
            stopRequested = false;
            state = State.RUNNING;
            finisher = new Thread(this::finishWhenDue, "bootui-profile-resources");
            finisher.setDaemon(true);
            finisher.start();
        } catch (IOException | RuntimeException | LinkageError ex) {
            JfrSegments.activate(false);
            closeQuietly();
            deleteQuietly(destination);
            state = State.FAILED;
            reason = "JDK Flight Recorder could not start: " + ex.getMessage();
            log.log(Level.FINE, "BootUI could not start a Profile resources session", ex);
        }
        return snapshot();
    }

    /** Ends the running session now and returns its results, once analysed; does nothing when none runs. */
    public Snapshot stop() {
        Thread running;
        synchronized (this) {
            if (state != State.RUNNING) {
                return snapshot();
            }
            stopRequested = true;
            notifyAll();
            running = finisher;
        }
        try {
            running.join(STOP_WAIT.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return snapshot();
    }

    private void finishWhenDue() {
        Object stopped;
        Path recorded;
        synchronized (this) {
            try {
                long wait;
                while (!stopRequested
                        && (wait = Duration.between(Instant.now(), endsAt).toMillis()) > 0) {
                    wait(wait);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            JfrSegments.activate(false);
            stopped = recording;
            recorded = file;
            recording = null;
            file = null;
        }
        Analysis result = null;
        String failure = null;
        try {
            Jfr.stop(stopped);
            result = Jfr.analyze(recorded, framework);
        } catch (IOException | RuntimeException | LinkageError ex) {
            failure = "The recording could not be read: " + ex.getMessage();
            log.log(Level.FINE, "BootUI could not analyse a Profile resources session", ex);
        } finally {
            deleteQuietly(recorded);
        }
        synchronized (this) {
            finishedAt = Instant.now();
            analysis = result;
            state = result == null ? State.FAILED : State.COMPLETED;
            reason = failure;
            finisher = null;
        }
    }

    private void closeQuietly() {
        if (recording != null) {
            try {
                Jfr.stop(recording);
            } catch (RuntimeException | LinkageError ex) {
                // Already stopped.
            }
            recording = null;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                // A temporary file left behind is removed with the temporary directory.
            }
        }
    }

    /** Joins one recording's samples to its segments; for tests, with a recording made outside a session. */
    static Analysis analyze(Path recording, Predicate<String> framework) throws IOException {
        return Jfr.analyze(recording, framework);
    }

    /** Where a session stands. */
    public enum State {
        /** No session ran since the application started. */
        IDLE,
        /** A session records now. */
        RUNNING,
        /** The last session ended and its samples were joined. */
        COMPLETED,
        /** The last session could not start or be read. */
        FAILED,
        /** This runtime has no JFR. */
        UNAVAILABLE
    }

    /**
     * A session's state and the last results.
     *
     * @param sampler the CPU sampler that ran, {@link #CPU_TIME_SAMPLER} or {@link #EXECUTION_SAMPLER}
     * @param otherRecording whether another JFR recording ran at the start, which may have raised the sampling rate
     */
    public record Snapshot(
            State state,
            String reason,
            String sampler,
            Instant startedAt,
            Instant endsAt,
            Instant finishedAt,
            Analysis analysis,
            boolean otherRecording) {}

    /**
     * One session's samples joined to requests.
     *
     * @param cpuSamples every CPU sample of the session
     * @param outsideSamples CPU samples taken while no request segment was open on their thread
     * @param requests each request's samples, by request id
     * @param requestsTruncated whether requests beyond {@link #MAX_REQUESTS} were left out
     */
    public record Analysis(
            long cpuSamples, long outsideSamples, Map<String, RequestSamples> requests, boolean requestsTruncated) {

        static final Analysis EMPTY = new Analysis(0, 0, Map.of(), false);

        public Analysis {
            requests = Collections.unmodifiableMap(new LinkedHashMap<>(requests));
        }
    }

    /**
     * One request's samples.
     *
     * @param cpuSamples CPU samples taken while one of its segments was open on the sampled thread
     * @param allocatedBytes the allocation samples' weights, JFR's estimate of the bytes it allocated
     * @param virtualThread whether any sample was taken on a virtual thread
     * @param frames each sample's first application frame, or its top frame when it has none, with its count
     */
    public record RequestSamples(
            long cpuSamples, long allocatedBytes, boolean virtualThread, Map<String, Long> frames) {

        public RequestSamples {
            frames = Collections.unmodifiableMap(new LinkedHashMap<>(frames));
        }
    }

    /** The only class that names {@code jdk.jfr}, loaded on the first use. */
    private static final class Jfr {

        private static final Method IS_VIRTUAL = isVirtual();

        static String unavailableReason() {
            return jdk.jfr.FlightRecorder.isAvailable() ? null : "JDK Flight Recorder is not available in this JVM.";
        }

        static boolean cpuTimeSampler() {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                return false;
            }
            for (jdk.jfr.EventType type :
                    jdk.jfr.FlightRecorder.getFlightRecorder().getEventTypes()) {
                if (CPU_TIME_SAMPLER.equals(type.getName())) {
                    return true;
                }
            }
            return false;
        }

        static Object start(Path destination, boolean cpuTime) throws IOException {
            jdk.jfr.FlightRecorder.register(ExecutionSegmentEvent.class);
            jdk.jfr.Recording recording = new jdk.jfr.Recording();
            recording.setName("BootUI Profile resources");
            recording.enable(SEGMENT_EVENT).withThreshold(Duration.ZERO);
            if (cpuTime) {
                recording.enable(CPU_TIME_SAMPLER).with("throttle", SAMPLE_PERIOD.toMillis() + "ms");
            } else {
                recording.enable(EXECUTION_SAMPLER).withPeriod(SAMPLE_PERIOD);
            }
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "150/s");
            recording.setToDisk(true);
            recording.setDestination(destination);
            recording.start();
            return recording;
        }

        static boolean otherRecordings(Object own) {
            for (jdk.jfr.Recording other :
                    jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) {
                if (other != own && other.getState() == jdk.jfr.RecordingState.RUNNING) {
                    return true;
                }
            }
            return false;
        }

        static void stop(Object recording) {
            if (recording == null) {
                return;
            }
            jdk.jfr.Recording jfr = (jdk.jfr.Recording) recording;
            try {
                if (jfr.getState() == jdk.jfr.RecordingState.RUNNING) {
                    jfr.stop();
                }
            } finally {
                jfr.close();
            }
        }

        static Analysis analyze(Path file, Predicate<String> framework) throws IOException {
            Map<Long, List<Segment>> segments = new HashMap<>();
            try (jdk.jfr.consumer.RecordingFile in = new jdk.jfr.consumer.RecordingFile(file)) {
                while (in.hasMoreEvents()) {
                    jdk.jfr.consumer.RecordedEvent event = in.readEvent();
                    if (!SEGMENT_EVENT.equals(event.getEventType().getName())) {
                        continue;
                    }
                    jdk.jfr.consumer.RecordedThread thread = event.getThread();
                    String requestId = event.getString("requestId");
                    if (thread != null && requestId != null) {
                        segments.computeIfAbsent(thread.getJavaThreadId(), id -> new ArrayList<>())
                                .add(new Segment(nanos(event.getStartTime()), nanos(event.getEndTime()), requestId));
                    }
                }
            }
            segments.values().forEach(list -> list.sort(Comparator.comparingLong(Segment::start)));
            long cpuSamples = 0;
            long outside = 0;
            boolean truncated = false;
            Map<String, Samples> requests = new LinkedHashMap<>();
            try (jdk.jfr.consumer.RecordingFile in = new jdk.jfr.consumer.RecordingFile(file)) {
                while (in.hasMoreEvents()) {
                    jdk.jfr.consumer.RecordedEvent event = in.readEvent();
                    String name = event.getEventType().getName();
                    boolean cpu = EXECUTION_SAMPLER.equals(name) || CPU_TIME_SAMPLER.equals(name);
                    if (!cpu && !"jdk.ObjectAllocationSample".equals(name)) {
                        continue;
                    }
                    if (cpu && event.hasField("failed") && event.getBoolean("failed")) {
                        continue;
                    }
                    jdk.jfr.consumer.RecordedThread thread =
                            event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
                    if (thread == null) {
                        continue;
                    }
                    if (cpu) {
                        cpuSamples++;
                    }
                    String owner = owner(segments.get(thread.getJavaThreadId()), nanos(event.getStartTime()));
                    if (owner == null) {
                        if (cpu) {
                            outside++;
                        }
                        continue;
                    }
                    Samples samples = requests.get(owner);
                    if (samples == null) {
                        if (requests.size() >= MAX_REQUESTS) {
                            truncated = true;
                            continue;
                        }
                        samples = new Samples();
                        requests.put(owner, samples);
                    }
                    samples.virtualThread |= virtual(thread);
                    if (cpu) {
                        samples.cpuSamples++;
                        String frame = frame(event.getStackTrace(), framework);
                        if (frame != null
                                && (samples.frames.containsKey(frame)
                                        || samples.frames.size() < MAX_FRAMES_PER_REQUEST)) {
                            samples.frames.merge(frame, 1L, Long::sum);
                        }
                    } else {
                        samples.allocatedBytes += Math.max(0, event.getLong("weight"));
                    }
                }
            }
            Map<String, RequestSamples> joined = new LinkedHashMap<>();
            requests.forEach((id, samples) -> joined.put(
                    id,
                    new RequestSamples(
                            samples.cpuSamples, samples.allocatedBytes, samples.virtualThread, samples.frames)));
            return new Analysis(cpuSamples, outside, joined, truncated);
        }

        /** The request whose segment was open on the thread at {@code time}, or {@code null}. */
        private static String owner(List<Segment> segments, long time) {
            if (segments == null) {
                return null;
            }
            int low = 0;
            int high = segments.size() - 1;
            int found = -1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (segments.get(middle).start() <= time) {
                    found = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return found >= 0 && segments.get(found).end() >= time
                    ? segments.get(found).requestId()
                    : null;
        }

        private static String frame(jdk.jfr.consumer.RecordedStackTrace stack, Predicate<String> framework) {
            if (stack == null) {
                return null;
            }
            String top = null;
            for (jdk.jfr.consumer.RecordedFrame frame : stack.getFrames()) {
                if (!frame.isJavaFrame() || frame.getMethod() == null) {
                    continue;
                }
                String type = frame.getMethod().getType().getName();
                String label = type + "." + frame.getMethod().getName()
                        + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
                if (top == null) {
                    top = label;
                }
                if (!framework.test(type)) {
                    return label;
                }
            }
            return top;
        }

        private static boolean virtual(jdk.jfr.consumer.RecordedThread thread) {
            try {
                return IS_VIRTUAL != null && Boolean.TRUE.equals(IS_VIRTUAL.invoke(thread));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }

        private static Method isVirtual() {
            try {
                // RecordedThread.isVirtual() is JDK 21+; the baseline is 17.
                return jdk.jfr.consumer.RecordedThread.class.getMethod("isVirtual");
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }

        private static long nanos(Instant instant) {
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        }
    }

    private record Segment(long start, long end, String requestId) {}

    private static final class Samples {

        private long cpuSamples;
        private long allocatedBytes;
        private boolean virtualThread;
        private final Map<String, Long> frames = new LinkedHashMap<>();
    }
}
