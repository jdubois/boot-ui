package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Publishes §5.18's control and availability markers to the {@code lifecycle} source ({@code docs/PLAN-v2.md}, M4-7):
 * BootUI's own actions, availability changes, configuration refreshes, and shutdown. A marker explains a discontinuity
 * in a run, such as a cache cleared mid-run. It names what was targeted, such as a logger, a cache, or a property key,
 * and never a value: an action keeps its method and path without the query, and never its body.
 */
public final class ControlMarkers {

    /** The longest target kept. */
    static final int MAX_TARGET = 200;

    /** The changed keys a configuration refresh names at most. */
    static final int MAX_KEYS = 10;

    /** Last path segments that name an action rather than what it targeted, such as {@code /cache/clear}. */
    private static final Set<String> VERBS = Set.of(
            "all",
            "analyze",
            "capture",
            "clean",
            "clear",
            "delete",
            "download",
            "enable",
            "install",
            "livereload",
            "migrate",
            "read",
            "recording",
            "refresh",
            "restart",
            "scan",
            "status",
            "stop",
            "update");

    private ControlMarkers() {}

    /**
     * Marks a BootUI action that succeeded, such as {@code POST /loggers/com.example}.
     *
     * @param panelId the panel whose action it was
     * @param apiRelativePath the action's path below the BootUI API, without its query
     */
    public static boolean action(RuntimeJournal journal, String panelId, String method, String apiRelativePath) {
        if (panelId == null || method == null || apiRelativePath == null) {
            return false;
        }
        String path = apiRelativePath;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return publish(journal, LifecyclePayload.ACTION, panelId + ": " + method.toUpperCase(Locale.ROOT) + " " + path);
    }

    /** Marks a liveness or readiness change, such as {@code Readiness REFUSING_TRAFFIC}. */
    public static boolean availability(RuntimeJournal journal, String state) {
        return state != null && publish(journal, LifecyclePayload.AVAILABILITY, state);
    }

    /** Marks a configuration refresh, naming at most {@value #MAX_KEYS} of the keys that changed. */
    public static boolean configRefresh(RuntimeJournal journal, Collection<String> keys) {
        List<String> names = keys == null ? List.of() : keys.stream().sorted().toList();
        String target = names.isEmpty()
                ? "no key changed"
                : String.join(", ", names.subList(0, Math.min(MAX_KEYS, names.size())))
                        + (names.size() > MAX_KEYS ? " and " + (names.size() - MAX_KEYS) + " more" : "");
        return publish(journal, LifecyclePayload.CONFIG_REFRESH, target);
    }

    /** Marks the application's shutdown. */
    public static boolean shutdown(RuntimeJournal journal) {
        return publish(journal, LifecyclePayload.SHUTDOWN, null);
    }

    /** The name an action's path targets, its last segment, such as a logger or cache name, or {@code null}. */
    public static String targetName(LifecyclePayload marker) {
        if (marker == null || !LifecyclePayload.ACTION.equals(marker.kind()) || marker.target() == null) {
            return null;
        }
        String target = marker.target();
        int slash = target.lastIndexOf('/');
        String name = slash < 0 ? null : target.substring(slash + 1);
        return name == null || name.length() < 3 || VERBS.contains(name) ? null : name;
    }

    private static boolean publish(RuntimeJournal journal, String kind, String target) {
        if (journal == null || !journal.records(JournalSource.LIFECYCLE)) {
            return false;
        }
        String bounded = target == null || target.length() <= MAX_TARGET ? target : target.substring(0, MAX_TARGET);
        return journal.offerMarker(RuntimeEvent.of(
                JournalSource.LIFECYCLE,
                System.currentTimeMillis(),
                0,
                CorrelationContext.NONE,
                null,
                null,
                false,
                new LifecyclePayload(kind, bounded, null)));
    }
}
