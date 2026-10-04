package io.github.jdubois.bootui.engine.codepaths;

import java.util.Arrays;

/**
 * Per method id, how many requests executed it (M5-7a): an open-addressing table of non-negative method ids, each with
 * its count and the stamp of the last request that counted it, so a method called many times in one request counts
 * that request once. 16 bytes per method, without boxing. Not thread-safe.
 */
final class MethodCounts {

    private static final int EMPTY = -1;

    private int[] keys = new int[16];
    private long[] counts = new long[16];
    private int[] stamps = new int[16];
    private int size;

    MethodCounts() {
        Arrays.fill(keys, EMPTY);
    }

    int size() {
        return size;
    }

    boolean contains(int id) {
        return id >= 0 && keys[slot(id)] == id;
    }

    /** The requests that executed method {@code id}; 0 when none did. */
    long count(int id) {
        if (id < 0) {
            return 0L;
        }
        int slot = slot(id);
        return keys[slot] == id ? counts[slot] : 0L;
    }

    /**
     * Counts one request, marked {@code stamp}, for method {@code id}, unless that request already counted it; a method
     * the table does not hold is added only when {@code mayAdd}. Returns whether the table holds it afterwards.
     */
    boolean count(int id, int stamp, boolean mayAdd) {
        int slot = slot(id);
        if (keys[slot] != id) {
            if (!mayAdd) {
                return false;
            }
            if ((size + 1) * 2 > keys.length) {
                grow();
                slot = slot(id);
            }
            keys[slot] = id;
            counts[slot] = 0L;
            stamps[slot] = stamp - 1;
            size++;
        }
        if (stamps[slot] != stamp) {
            stamps[slot] = stamp;
            counts[slot]++;
        }
        return true;
    }

    /** Every method id held, unordered: a copy. */
    int[] ids() {
        int[] ids = new int[size];
        int at = 0;
        for (int key : keys) {
            if (key != EMPTY) {
                ids[at++] = key;
            }
        }
        return ids;
    }

    private int slot(int id) {
        int mask = keys.length - 1;
        int slot = mix(id) & mask;
        while (keys[slot] != EMPTY && keys[slot] != id) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static int mix(int id) {
        int h = id * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    private void grow() {
        int[] oldKeys = keys;
        long[] oldCounts = counts;
        int[] oldStamps = stamps;
        keys = new int[oldKeys.length * 2];
        counts = new long[keys.length];
        stamps = new int[keys.length];
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int slot = slot(oldKeys[i]);
                keys[slot] = oldKeys[i];
                counts[slot] = oldCounts[i];
                stamps[slot] = oldStamps[i];
            }
        }
    }
}
