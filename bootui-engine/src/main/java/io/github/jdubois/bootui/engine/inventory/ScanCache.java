package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.ClassHashes;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The class-file hashes of the previous scans, by file path, size, and a stamp, the modification time of a file or the
 * CRC-32 of a jar entry ({@code docs/PLAN-v2.md} §5.15), so a restart re-parses only the class files that changed; and
 * whether each class-path jar, by path, size, and modification time, holds the claimed packages, so a restart opens only
 * the jars that changed. Kept in the {@link CodeInventoryHistory} across
 * DevTools restarts and Quarkus live reloads, it holds JDK types only (strings and primitive arrays), never a class,
 * class loader, or object of the run that hashed it. Bounded: past its size, the least recently used entries go.
 */
public final class ScanCache {

    private static final int SIZE = 0;
    private static final int MODIFIED = 1;
    private static final int CLASS_NAME = 2;
    private static final int ACCESS = 3;
    private static final int NAMES = 4;
    private static final int DESCRIPTORS = 5;
    private static final int METHOD_ACCESS = 6;
    private static final int CODE_HASHES = 7;

    /** The most class-path jars whose answer is kept. */
    static final int MAX_JARS = 4096;

    private final int maxEntries;
    private final LinkedHashMap<String, Object[]> entries;
    private final LinkedHashMap<String, Boolean> jars = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_JARS;
        }
    };

    /** A cache of at most {@code maxEntries} class files. */
    public ScanCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                return size() > ScanCache.this.maxEntries;
            }
        };
    }

    /** The hashes of the class file at {@code key}, when its size and stamp are unchanged, else null. */
    public synchronized ClassHashes get(String key, long size, long stamp) {
        Object[] entry = entries.get(key);
        if (entry == null || (Long) entry[SIZE] != size || (Long) entry[MODIFIED] != stamp) {
            return null;
        }
        String[] names = (String[]) entry[NAMES];
        String[] descriptors = (String[]) entry[DESCRIPTORS];
        int[] access = (int[]) entry[METHOD_ACCESS];
        int[] codes = (int[]) entry[CODE_HASHES];
        List<MethodHash> methods = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            boolean hasCode = (access[i] & (1 << 31)) != 0;
            methods.add(new MethodHash(names[i], descriptors[i], access[i] & 0xFFFF, hasCode, codes[i]));
        }
        return new ClassHashes((String) entry[CLASS_NAME], (Integer) entry[ACCESS], methods);
    }

    /** Keeps the hashes of the class file at {@code key}, read with its size and stamp. */
    public synchronized void put(String key, long size, long stamp, ClassHashes hashes) {
        int count = hashes.methods().size();
        String[] names = new String[count];
        String[] descriptors = new String[count];
        int[] access = new int[count];
        int[] codes = new int[count];
        for (int i = 0; i < count; i++) {
            MethodHash method = hashes.methods().get(i);
            names[i] = method.name();
            descriptors[i] = method.descriptor();
            access[i] = (method.access() & 0xFFFF) | (method.hasCode() ? 1 << 31 : 0);
            codes[i] = method.codeHash();
        }
        entries.put(
                key,
                new Object[] {size, stamp, hashes.className(), hashes.access(), names, descriptors, access, codes});
    }

    /** Whether the jar {@code key} names (path, size, modification time, and packages) holds them, or null. */
    public synchronized Boolean jarHolds(String key) {
        return jars.get(key);
    }

    /** Keeps whether the jar {@code key} names holds its packages. */
    public synchronized void jarHolds(String key, boolean holds) {
        jars.put(key, holds);
    }

    /** How many class-path jars' answers it keeps. */
    synchronized int jars() {
        return jars.size();
    }

    /** How many class files it keeps. */
    public synchronized int size() {
        return entries.size();
    }
}
