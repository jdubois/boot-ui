package io.github.jdubois.bootui.engine.journal;

import java.util.EnumSet;
import java.util.Set;

/**
 * The bounds of a {@link RuntimeJournal}, from the {@code bootui.runtime-journal.*} properties ({@code
 * docs/PLAN-v2.md} §5.2).
 *
 * @param enabled whether the journal records anything
 * @param maxEvents the most events retained as evidence
 * @param maxBytes the most bytes retained as evidence, dictionary included
 * @param queueCapacity the most events waiting for the dispatcher; the last {@link #reservedQueueSharePercent()} of it
 *     admits only failed or slow events
 * @param reservedSharePercent the share of {@code maxEvents} kept for failed and slow events
 * @param reservedQueueSharePercent the share of {@code queueCapacity} kept for failed and slow events
 * @param sources the sources recorded
 */
public record RuntimeJournalSettings(
        boolean enabled,
        int maxEvents,
        long maxBytes,
        int queueCapacity,
        int reservedSharePercent,
        int reservedQueueSharePercent,
        Set<JournalSource> sources) {

    public static final int DEFAULT_MAX_EVENTS = 50_000;

    /** The byte bound when the heap is large enough: 32 MB. */
    public static final long DEFAULT_MAX_BYTES_CAP = 32L * 1024 * 1024;

    /** The byte bound's share of the maximum heap, when that is smaller than {@link #DEFAULT_MAX_BYTES_CAP}. */
    public static final int DEFAULT_MAX_HEAP_PERCENT = 5;

    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    public static final int DEFAULT_RESERVED_SHARE_PERCENT = 10;

    public RuntimeJournalSettings {
        maxEvents = Math.max(1, maxEvents);
        maxBytes = Math.max(1, maxBytes);
        queueCapacity = Math.max(1, queueCapacity);
        reservedSharePercent = Math.min(100, Math.max(0, reservedSharePercent));
        reservedQueueSharePercent = Math.min(100, Math.max(0, reservedQueueSharePercent));
        sources = sources == null || sources.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(sources));
    }

    /** The defaults: enabled, 50,000 events, {@link #defaultMaxBytes}, a 10,000-event queue, and every source. */
    public static RuntimeJournalSettings defaults() {
        return new RuntimeJournalSettings(
                true,
                DEFAULT_MAX_EVENTS,
                defaultMaxBytes(Runtime.getRuntime().maxMemory()),
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_RESERVED_SHARE_PERCENT,
                DEFAULT_RESERVED_SHARE_PERCENT,
                JournalSource.all());
    }

    /**
     * The settings the {@code bootui.runtime-journal.*} properties describe.
     *
     * @param maxBytes the byte bound, or {@code null} or a non-positive value for {@link #defaultMaxBytes}
     * @param sources a comma-separated list of {@link JournalSource} names, or {@code null} for every source
     * @throws IllegalArgumentException naming an unknown source
     */
    public static RuntimeJournalSettings of(
            boolean enabled, int maxEvents, Long maxBytes, int queueCapacity, String sources) {
        return new RuntimeJournalSettings(
                enabled,
                maxEvents,
                maxBytes == null || maxBytes <= 0
                        ? defaultMaxBytes(Runtime.getRuntime().maxMemory())
                        : maxBytes,
                queueCapacity,
                DEFAULT_RESERVED_SHARE_PERCENT,
                DEFAULT_RESERVED_SHARE_PERCENT,
                sources == null ? JournalSource.all() : JournalSource.parse(sources));
    }

    /**
     * Parses a byte size written as Spring's {@code DataSize} or Quarkus's {@code MemorySize} writes it: a number of
     * bytes, optionally followed by {@code B}, {@code K} or {@code KB}, {@code M} or {@code MB}, or {@code G} or
     * {@code GB}, in powers of 1,024 and ignoring case, so {@code 32MB} and {@code 32M} mean the same on every stack.
     *
     * @return the bytes, or {@code null} for a {@code null} or blank value
     * @throws IllegalArgumentException for anything else
     */
    public static Long parseBytes(String value) {
        return parseBytes(value, "bootui.runtime-journal.max-bytes");
    }

    /** {@link #parseBytes(String)} for the key {@code property}, which an invalid value's message names. */
    public static Long parseBytes(String value, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\s*(\\d+)\\s*(B|K|KB|M|MB|G|GB)?\\s*")
                .matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid byte size '" + value + "' for " + property
                    + ": use a number of bytes, optionally followed by KB, MB, or GB.");
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(java.util.Locale.ROOT);
        int shift =
                switch (unit.charAt(0)) {
                    case 'K' -> 10;
                    case 'M' -> 20;
                    case 'G' -> 30;
                    default -> 0;
                };
        return Math.multiplyExact(amount, 1L << shift);
    }

    /** A disabled journal, which records nothing. */
    public static RuntimeJournalSettings disabled() {
        return new RuntimeJournalSettings(
                false,
                DEFAULT_MAX_EVENTS,
                DEFAULT_MAX_BYTES_CAP,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_RESERVED_SHARE_PERCENT,
                DEFAULT_RESERVED_SHARE_PERCENT,
                Set.of());
    }

    /**
     * The default byte bound: the smaller of 32 MB and 5 % of {@code maxHeapBytes}, or 32 MB when the heap reports no
     * limit.
     */
    public static long defaultMaxBytes(long maxHeapBytes) {
        if (maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE) {
            return DEFAULT_MAX_BYTES_CAP;
        }
        return Math.max(1, Math.min(DEFAULT_MAX_BYTES_CAP, maxHeapBytes * DEFAULT_MAX_HEAP_PERCENT / 100));
    }

    /** Whether the journal records events of {@code source}. */
    public boolean records(JournalSource source) {
        return enabled && sources.contains(source);
    }

    /** The queue depth from which only failed or slow events are admitted. */
    public int routineQueueLimit() {
        long reserved = (long) queueCapacity * reservedQueueSharePercent / 100L;
        return (int) Math.max(1, queueCapacity - Math.min(reserved, queueCapacity - 1L));
    }
}
