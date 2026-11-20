package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The proven continuous-capture window of each observable synchronous cache manager. This qualifies only absence
 * inference, never the readability of retained events. Manager routing is proven by the adapter, not by this record.
 */
public record CacheCapture(
        Map<String, Long> continuousSince,
        String defaultManager,
        long epoch,
        String reason,
        Map<String, String> canonicalManagers) {

    public static final String DISABLED = "Cache activity capture is disabled for this recorder"
            + " (bootui.cache.activity-capture-enabled or the Cache panel's startup policy),"
            + " so cache accesses are not recorded.";
    public static final String NOT_RECORDED =
            "Cache accesses are not recorded: no cache activity recorder with an instrumented cache manager is available.";
    public static final String PAUSED =
            "Cache activity capture is paused for low-footprint mode, so cache accesses are not recorded.";
    public static final String BEFORE_WINDOW =
            "The request started before the current proven continuous cache-capture window.";
    public static final String UNPROVEN_MANAGER =
            "The cache manager selected for this method is not proven observable.";
    public static final String OBSERVATION_FAILED =
            "A cache observation failed; this manager's continuous capture is not proven.";
    public static final String CHANGED_DURING_READ =
            "Cache capture changed while retained events were read; missing accesses were not assessed.";

    public CacheCapture {
        continuousSince = Map.copyOf(continuousSince);
        canonicalManagers = Map.copyOf(canonicalManagers);
    }

    /** Capture metadata from code predating manager-alias provenance. */
    public CacheCapture(Map<String, Long> continuousSince, String defaultManager, long epoch, String reason) {
        this(continuousSince, defaultManager, epoch, reason, Map.of());
    }

    /** Complete evidence supplied by controlled engine fixtures, not a production availability default. */
    public static CacheCapture capturing() {
        return new CacheCapture(Map.of("", Long.MIN_VALUE), "", 0, null);
    }

    public static CacheCapture notRecorded(String reason) {
        return new CacheCapture(Map.of(), null, 0, reason == null || reason.isBlank() ? NOT_RECORDED : reason);
    }

    /** Reads only BootUI's recorder state, never a cache manager or application callback. */
    public static CacheCapture of(CacheActivityRecorder recorder) {
        if (recorder == null) return notRecorded(NOT_RECORDED);
        return of(recorder.captureState());
    }

    public static CacheCapture of(CacheActivityRecorder.CaptureState state) {
        String reason = !state.enabled()
                ? DISABLED
                : state.paused()
                        ? PAUSED
                        : !state.journalInstalled()
                                ? "The cache recorder is not connected to the runtime journal."
                                : state.exceeded()
                                        ? "Cache manager capture tracking exceeded its bounded capacity."
                                        : state.continuousSince().isEmpty() ? NOT_RECORDED : null;
        Map<String, Long> windows = reason == null ? state.continuousSince() : Map.of();
        return new CacheCapture(
                windows, windows.size() == 1 ? windows.keySet().iterator().next() : null, state.epoch(), reason);
    }

    /** Why this request cannot establish absence for the selected manager, or null when continuously observed. */
    public String unavailable(String manager, long requestStartMillis) {
        if (reason != null) return reason;
        String selected = selectedManager(manager);
        Long since = selected == null ? null : continuousSince.get(selected);
        if (since == null) return UNPROVEN_MANAGER;
        if (since == Long.MAX_VALUE) return OBSERVATION_FAILED;
        // A request in the installation/resume millisecond cannot be ordered reliably against that transition.
        return requestStartMillis > since ? null : BEFORE_WINDOW;
    }

    /** Resolves the manager used by a boundary against the adapter's proven default. */
    String selectedManager(String manager) {
        return manager == null || manager.isBlank() ? defaultManager : manager;
    }

    /** The recorder identity corresponding to a declared manager name or alias. */
    String canonicalManager(String manager) {
        String selected = selectedManager(manager);
        return selected == null ? null : canonicalManagers.getOrDefault(selected, selected);
    }

    /** Keeps known routing identity while withholding absence inference after a read-time change. */
    CacheCapture withReason(String reason) {
        return new CacheCapture(continuousSince, defaultManager, epoch, reason, canonicalManagers);
    }

    /** Missing, failed, or ambiguous adapter evidence never proves that capture was installed. */
    public static CacheCapture read(Supplier<CacheCapture> supplier) {
        if (supplier == null) {
            return notRecorded(NOT_RECORDED);
        }
        try {
            CacheCapture capture = supplier.get();
            return capture == null ? notRecorded(NOT_RECORDED) : capture;
        } catch (RuntimeException | LinkageError ex) {
            return notRecorded("Whether this application's cache accesses are recorded could not be read ("
                    + ex.getClass().getSimpleName() + "), so their absence is not judged.");
        }
    }
}
