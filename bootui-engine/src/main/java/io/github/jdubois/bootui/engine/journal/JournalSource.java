package io.github.jdubois.bootui.engine.journal;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A source of runtime facts the journal records ({@code docs/PLAN-v2.md} §5.2), named as
 * {@code bootui.runtime-journal.sources} lists it.
 *
 * <p>Every source but {@link #RESOURCES} publishes events. {@code resources} enables §5.11's CPU ledger and resource
 * track, which the journal keeps in fixed rings beside its events rather than as events.</p>
 */
public enum JournalSource {
    HTTP("http"),
    SQL("sql"),
    TRANSACTION("transaction"),
    EXCEPTION("exception"),
    SECURITY("security"),
    REST_CLIENT("rest-client"),
    CACHE("cache"),
    MESSAGING("messaging"),
    SCHEDULED("scheduled"),
    LOG("log"),
    GC("gc"),
    RESOURCES("resources");

    private final String propertyName;

    JournalSource(String propertyName) {
        this.propertyName = propertyName;
    }

    /** The name {@code bootui.runtime-journal.sources} uses, such as {@code rest-client}. */
    public String propertyName() {
        return propertyName;
    }

    /** Whether the source publishes events, as every source but {@link #RESOURCES} does. */
    public boolean publishesEvents() {
        return this != RESOURCES;
    }

    /** Every source, which is the default. */
    public static Set<JournalSource> all() {
        return EnumSet.allOf(JournalSource.class);
    }

    /**
     * Parses a comma-separated list of source names, ignoring case and blanks. An unknown name is rejected, never
     * ignored, so a typo cannot silently switch a source off.
     *
     * @throws IllegalArgumentException naming the unknown source and the valid ones
     */
    public static Set<JournalSource> parse(String names) {
        EnumSet<JournalSource> sources = EnumSet.noneOf(JournalSource.class);
        if (names == null) {
            return sources;
        }
        for (String raw : names.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            sources.add(Arrays.stream(values())
                    .filter(source -> source.propertyName.equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown runtime journal source '" + raw.trim()
                            + "'. Valid sources: "
                            + Arrays.stream(values())
                                    .map(JournalSource::propertyName)
                                    .collect(Collectors.joining(", "))
                            + ".")));
        }
        return sources;
    }
}
