package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns the strings events repeat, such as route templates, statement fingerprints, call sites, and thread
 * families, so events hold small integer codes ({@code docs/PLAN-v2.md} §5.2). One dictionary serves one run.
 *
 * <p>It is bounded by entries and by bytes, and its bytes count against the journal's byte bound. Once full, it interns
 * nothing more: {@link #intern} returns {@link #NOT_INTERNED}, and the event keeps its own string, which then counts in
 * its own size. Codes are never reused, so a code read from an event always names the same string.</p>
 */
public final class JournalDictionary {

    /** The code {@link #intern} returns for a {@code null} string or once the dictionary is full. */
    public static final int NOT_INTERNED = -1;

    /** Bytes a payload retains for a string the dictionary shares: one reference. */
    public static final int REFERENCE_BYTES = 8;

    private final int maxEntries;
    private final long maxBytes;
    private final Map<String, Integer> codes = new HashMap<>();
    private final List<String> strings = new ArrayList<>();
    private long bytes;

    public JournalDictionary(int maxEntries, long maxBytes) {
        this.maxEntries = Math.max(0, maxEntries);
        this.maxBytes = Math.max(0, maxBytes);
    }

    /** The code of {@code value}, interning it if there is room, or {@link #NOT_INTERNED}. */
    public synchronized int intern(String value) {
        if (value == null) {
            return NOT_INTERNED;
        }
        Integer code = codes.get(value);
        if (code != null) {
            return code;
        }
        int cost = entryBytes(value);
        if (strings.size() >= maxEntries || bytes + cost > maxBytes) {
            return NOT_INTERNED;
        }
        int next = strings.size();
        strings.add(value);
        codes.put(value, next);
        bytes += cost;
        return next;
    }

    /**
     * The dictionary's shared copy of {@code value}, interning it if there is room, or {@code null} when the
     * dictionary is full.
     */
    public synchronized String canonical(String value) {
        int code = intern(value);
        return code == NOT_INTERNED ? null : strings.get(code);
    }

    /** The run's shared copy of {@code value}, or {@code value} itself once the dictionary is full. */
    public String shared(String value) {
        String canonical = canonical(value);
        return canonical == null ? value : canonical;
    }

    /**
     * The bytes a payload retains for {@code value}: a reference when {@code value} is this dictionary's shared copy,
     * as {@link #shared} returns it, otherwise the string itself, since a full dictionary leaves the payload its own
     * copy.
     */
    public synchronized int retainedBytes(String value) {
        if (value == null) {
            return 0;
        }
        Integer code = codes.get(value);
        return code != null && strings.get(code) == value ? REFERENCE_BYTES : RuntimeEvent.stringBytes(value);
    }

    /**
     * The bytes a payload retains for {@code value} with {@code dictionary}'s sharing, or as its own string when
     * {@code dictionary} is {@code null}.
     */
    static int retained(JournalDictionary dictionary, String value) {
        return dictionary == null ? RuntimeEvent.stringBytes(value) : dictionary.retainedBytes(value);
    }

    /** The string {@code code} names, or {@code null} for {@link #NOT_INTERNED} or an unknown code. */
    public synchronized String lookup(int code) {
        return code < 0 || code >= strings.size() ? null : strings.get(code);
    }

    public synchronized int size() {
        return strings.size();
    }

    /** The bytes the dictionary retains, counted against the journal's byte bound. */
    public synchronized long bytes() {
        return bytes;
    }

    /** A string, its map entry, and its list slot. */
    private static int entryBytes(String value) {
        return RuntimeEvent.stringBytes(value) + 48;
    }
}
