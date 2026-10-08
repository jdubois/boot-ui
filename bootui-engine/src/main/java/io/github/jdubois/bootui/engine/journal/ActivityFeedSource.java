package io.github.jdubois.bootui.engine.journal;

import java.util.Locale;

/**
 * Where Live Activity's feed comes from ({@code docs/PLAN-v2.md} §5.3). The runtime journal is the only source
 * {@code bootui.activity.feed-source} names since 2.0.0. The panel buffers still serve the feed while the journal does
 * not record ({@code bootui.runtime-journal.enabled=false}), and a request may still ask for them with
 * {@code ?source=buffers}, as a diagnostic that compares the two.
 */
public enum ActivityFeedSource {
    /** The feed merged from each panel's own buffer: the journal-off fallback and a request's diagnostic override. */
    BUFFERS,
    /** The feed rendered from the runtime journal's retained events. */
    JOURNAL;

    /** The default source. */
    public static final ActivityFeedSource DEFAULT = JOURNAL;

    /** The property that names the configured source. */
    public static final String PROPERTY = "bootui.activity.feed-source";

    /** Why {@code bootui.activity.feed-source=buffers} fails the start. */
    public static final String BUFFERS_REMOVED = PROPERTY
            + "=buffers was removed in BootUI 2.0.0: Live Activity reads the runtime journal. Remove the property, or set"
            + " it to journal; with bootui.runtime-journal.enabled=false, the panel buffers serve the feed on their own.";

    /**
     * Parses {@code buffers} or {@code journal}, ignoring case, as a request's {@code ?source=} names it.
     *
     * @param value the name, or {@code null} or blank for {@code fallback}
     * @throws IllegalArgumentException naming the valid sources, for any other value
     */
    public static ActivityFeedSource parse(String value, ActivityFeedSource fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown Live Activity feed source '" + value.trim() + "'. Valid sources: buffers, journal.");
        }
    }

    /**
     * Parses {@value #PROPERTY}: {@code journal}, ignoring case, or {@link #DEFAULT} when it is {@code null} or blank.
     *
     * @throws IllegalArgumentException for {@code buffers}, which 2.0.0 removed ({@link #BUFFERS_REMOVED}), and for
     *     any other value
     */
    public static ActivityFeedSource parseConfigured(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        if ("buffers".equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException(BUFFERS_REMOVED);
        }
        if ("journal".equalsIgnoreCase(value.trim())) {
            return JOURNAL;
        }
        throw new IllegalArgumentException("Unknown " + PROPERTY + " '" + value.trim() + "'. Valid value: journal.");
    }
}
