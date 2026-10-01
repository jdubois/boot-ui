package io.github.jdubois.bootui.engine.journal;

import java.util.Locale;

/**
 * Where Live Activity's feed comes from ({@code docs/PLAN-v2.md} §5.3): {@code bootui.activity.feed-source}, which a
 * request may override with {@code ?source=}. The journal is the default since its feed reached parity on every stack;
 * {@code buffers} keeps 1.x's feed until 2.0.0 removes it.
 */
public enum ActivityFeedSource {
    /** 1.x: the feed merged from each panel's own buffer. */
    BUFFERS,
    /** The feed rendered from the runtime journal's retained events. */
    JOURNAL;

    /** The default source. */
    public static final ActivityFeedSource DEFAULT = JOURNAL;

    /**
     * Parses {@code buffers} or {@code journal}, ignoring case.
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
}
