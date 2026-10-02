package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The summaries of the most recent application runs, kept across DevTools restarts and Quarkus live reloads in the
 * same JVM ({@code docs/PLAN-v2.md} §5.2, §5.8).
 *
 * <p>BootUI's jars stay in the class loader that survives those restarts, so the {@linkplain #shared() shared}
 * history, a static field, outlives the application context that recorded each run. It keeps each summary as an
 * encoded byte array, which is a JDK type, so a kept run pins no class of the application, its frameworks, or the
 * context that ended. When BootUI is itself loaded by the reloadable class loader, the history restarts with the
 * application and keeps nothing; {@link #unavailableReason()} says so, rather than reporting that there was no
 * previous run.</p>
 */
public final class RunHistory {

    /** The runs kept, newest first. */
    public static final int MAX_RUNS = 5;

    /** The largest encoded summary of one run. */
    public static final int MAX_SUMMARY_BYTES = 256 * 1024;

    static final String SPRING_RESTART_CLASS_LOADER =
            "org.springframework.boot.devtools.restart.classloader.RestartClassLoader";

    static final String QUARKUS_CLASS_LOADER = "io.quarkus.bootstrap.classloading.QuarkusClassLoader";

    static final String QUARKUS_RELOADABLE_LOADER_NAME = "Quarkus Runtime ClassLoader";

    private static final Logger log = Logger.getLogger(RunHistory.class.getName());

    private static final RunHistory SHARED =
            new RunHistory(MAX_RUNS, MAX_SUMMARY_BYTES, reloadableReason(RunHistory.class.getClassLoader()));

    private final int maxRuns;
    private final int maxSummaryBytes;
    private final String unavailableReason;
    private final ArrayDeque<byte[]> runs = new ArrayDeque<>();
    private volatile String baselineNote;
    private volatile String baselineRunId;

    RunHistory(int maxRuns, int maxSummaryBytes, String unavailableReason) {
        this.maxRuns = maxRuns;
        this.maxSummaryBytes = maxSummaryBytes;
        this.unavailableReason = unavailableReason;
    }

    /** The history of this JVM, which survives application restarts unless BootUI itself is reloaded. */
    public static RunHistory shared() {
        return SHARED;
    }

    /**
     * Keeps the summary of a run that ended, evicting the oldest kept run beyond {@value #MAX_RUNS}. Recording the same
     * run again replaces it. Never throws: a summary that cannot be encoded is logged and skipped.
     */
    public void record(RunSummary summary) {
        byte[] encoded;
        try {
            encoded = RunSummaryCodec.encode(summary, maxSummaryBytes);
        } catch (RuntimeException ex) {
            log.log(
                    Level.WARNING,
                    "BootUI could not keep the summary of run "
                            + summary.header().runId(),
                    ex);
            return;
        }
        synchronized (runs) {
            Iterator<byte[]> kept = runs.iterator();
            while (kept.hasNext()) {
                if (RunSummaryCodec.header(kept.next())
                        .runId()
                        .equals(summary.header().runId())) {
                    kept.remove();
                }
            }
            runs.addFirst(encoded);
            while (runs.size() > maxRuns) {
                runs.removeLast();
            }
        }
    }

    /**
     * Reads {@code baseline} as the previous run when this history keeps none, as after a full JVM restart or when
     * BootUI is reloaded with the application ({@code docs/PLAN-v2.md} §5.8). Never throws: a file that cannot be used
     * is ignored, and {@link #baselineNote()} says why.
     */
    public void loadBaseline(RunBaselineFile baseline) {
        if (baseline == null) {
            return;
        }
        synchronized (runs) {
            if (!runs.isEmpty()) {
                return;
            }
            RunBaselineFile.Read read = baseline.read();
            if (read.summary() != null) {
                record(read.summary());
                baselineRunId = read.summary().header().runId();
                baselineNote = "The previous run was read from the baseline file " + baseline.path() + ".";
            } else {
                baselineNote = read.ignoredReason();
                if (read.ignoredReason() != null) {
                    log.info(read.ignoredReason());
                }
            }
        }
    }

    /**
     * Whether the previous run came from the baseline file, or why that file was ignored, or {@code null} when no
     * baseline file was read.
     */
    public String baselineNote() {
        return baselineNote;
    }

    /** The id of the kept run read from the baseline file, or {@code null} when none was. */
    public String baselineRunId() {
        return baselineRunId;
    }

    /** The kept runs' headers, newest first, without decoding their aggregates. */
    public List<RunSummary.Header> headers() {
        List<RunSummary.Header> headers = new ArrayList<>();
        for (byte[] run : snapshot()) {
            headers.add(RunSummaryCodec.header(run));
        }
        return headers;
    }

    /** The kept runs' summaries, newest first. */
    public List<RunSummary> summaries() {
        List<RunSummary> summaries = new ArrayList<>();
        for (byte[] run : snapshot()) {
            summaries.add(RunSummaryCodec.decode(run));
        }
        return summaries;
    }

    /** The bytes the kept summaries use. */
    public long bytes() {
        return snapshot().stream().mapToLong(run -> run.length).sum();
    }

    /**
     * Why previous runs cannot be kept, or {@code null} when they can: BootUI is loaded by the class loader that is
     * replaced on every restart, so this history is replaced with it.
     */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** Drops every kept run; for tests. */
    void clear() {
        synchronized (runs) {
            runs.clear();
            baselineNote = null;
            baselineRunId = null;
        }
    }

    private List<byte[]> snapshot() {
        synchronized (runs) {
            return List.copyOf(runs);
        }
    }

    /** Why a history loaded by {@code loader} would be replaced on every restart, or {@code null}. */
    static String reloadableReason(ClassLoader loader) {
        return loader == null ? null : reloadableReason(loader.getClass().getName(), loader.getName());
    }

    static String reloadableReason(String loaderClass, String loaderName) {
        if (SPRING_RESTART_CLASS_LOADER.equals(loaderClass)) {
            return "BootUI is loaded by Spring DevTools' restart class loader, for example through"
                    + " spring.devtools.restart.include, so it restarts with the application and keeps no previous run.";
        }
        if (QUARKUS_CLASS_LOADER.equals(loaderClass)
                && loaderName != null
                && loaderName.startsWith(QUARKUS_RELOADABLE_LOADER_NAME)) {
            return "BootUI is loaded by Quarkus's reloadable class loader, for example through"
                    + " quarkus.class-loading.reloadable-artifacts, so it reloads with the application and keeps no"
                    + " previous run.";
        }
        return null;
    }
}
