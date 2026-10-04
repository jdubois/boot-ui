package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import io.github.jdubois.bootui.engine.inventory.ClassScanner.ScannedClass;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code Inventory's run history ({@code docs/PLAN-v2.md} §5.8, §5.15), kept across DevTools restarts and Quarkus live
 * reloads in the same JVM beside the run summaries of {@link RunHistory}, for the same reason: BootUI's jars stay in the
 * class loader that survives those restarts, so this static holder outlives the run that recorded each entry. It keeps,
 * per claim slot ({@code mode:application}), the method hashes of the current and the previous run, each as a sorted
 * {@code long[]} of 64-bit method-key hashes and an aligned {@code int[]} of 32-bit code hashes (12 bytes a method, at
 * most {@value #MAX_PAIR_BYTES} bytes, beyond which a run is kept partial), with the packages its scan covered, and the
 * {@link ScanCache} of class-file hashes. Only JDK types are kept: never a class, class loader, or object of a run.
 *
 * <p>When BootUI is itself loaded by the reloadable class loader, this holder restarts with the application and keeps
 * nothing; {@link #unavailableReason()} says so rather than reporting that there was no previous run.
 */
public final class CodeInventoryHistory {

    /** The most bytes one run's method hashes use. */
    public static final int MAX_PAIR_BYTES = 1 << 20;

    /** The most methods one run keeps: 12 bytes each. */
    public static final int MAX_PAIRS = MAX_PAIR_BYTES / 12;

    /** The most claim slots kept. */
    static final int MAX_SLOTS = 8;

    /** The most class files the scan cache keeps. */
    static final int CACHE_ENTRIES = 50_000;

    private static final CodeInventoryHistory SHARED = new CodeInventoryHistory(
            RunHistory.reloadableReason(CodeInventoryHistory.class.getClassLoader()), new ScanCache(CACHE_ENTRIES));

    private final String unavailableReason;
    private final ScanCache cache;
    private final LinkedHashMap<String, KeptRun[]> slots = new LinkedHashMap<>();
    private String lastSlot;
    private final Map<String, Boolean> interleaved = new LinkedHashMap<>();
    private long version;

    CodeInventoryHistory(String unavailableReason, ScanCache cache) {
        this.unavailableReason = unavailableReason;
        this.cache = cache;
    }

    /** The history of this JVM, which survives application restarts unless BootUI itself is reloaded. */
    public static CodeInventoryHistory shared() {
        return SHARED;
    }

    /** The class-file hash cache kept with this history. */
    public ScanCache cache() {
        return cache;
    }

    /** Why previous runs cannot be kept, or {@code null} when they can. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /**
     * Keeps {@code run} as the current run of {@code slot}: the run it replaces becomes the previous one when it belongs
     * to an older claim generation, and is replaced when it is the same run scanned again. A run older than the current
     * one, as a scan of an ended run finishing late, is ignored: it never reorders the history. A caller never records a
     * cancelled scan. Returns whether it was kept.
     */
    public synchronized boolean record(String slot, KeptRun run) {
        KeptRun[] existing = slots.get(slot);
        if (existing != null && existing[0] != null && run.generation() < existing[0].generation()) {
            return false;
        }
        KeptRun[] runs = existing;
        if (runs == null) {
            while (slots.size() >= MAX_SLOTS) {
                String eldest = slots.keySet().iterator().next();
                slots.remove(eldest);
                interleaved.remove(eldest);
            }
            runs = new KeptRun[2];
            slots.put(slot, runs);
        }
        if (runs[0] != null && runs[0].generation() != run.generation()) {
            runs[1] = runs[0];
            interleaved.put(slot, lastSlot != null && !lastSlot.equals(slot));
        }
        runs[0] = run;
        lastSlot = slot;
        version++;
        return true;
    }

    /** A number that changes whenever a run is recorded: part of a cheap fingerprint of what the history answers. */
    public synchronized long version() {
        return version;
    }

    /**
     * The run of {@code slot} before claim {@code generation}: the previous one once {@code generation} is recorded, the
     * current one while it is not yet; {@code null} when there is none.
     */
    public synchronized KeptRun previous(String slot, long generation) {
        KeptRun[] runs = slots.get(slot);
        if (runs == null || runs[0] == null) {
            return null;
        }
        if (runs[0].generation() == generation) {
            return runs[1];
        }
        return runs[0].generation() < generation ? runs[0] : null;
    }

    /**
     * Whether another application claimed the agent between {@code slot}'s previous run and its run of {@code
     * generation}: its runs are then mixed with another's in this JVM.
     */
    public synchronized boolean mixed(String slot, long generation) {
        KeptRun[] runs = slots.get(slot);
        if (runs == null || runs[0] == null) {
            return false;
        }
        if (runs[0].generation() == generation) {
            return Boolean.TRUE.equals(interleaved.get(slot));
        }
        return lastSlot != null && !lastSlot.equals(slot);
    }

    /** Drops every kept run; for tests. */
    synchronized void clear() {
        slots.clear();
        interleaved.clear();
        lastSlot = null;
        version++;
    }

    /**
     * One run's method hashes: JDK types only.
     *
     * @param generation the claim generation of the run
     * @param keys the 64-bit method-key hashes, sorted
     * @param codes the 32-bit code hashes, aligned with {@code keys}
     * @param packages the packages its scan covered
     * @param complete whether its scan covered every class of {@code packages} and every method was kept
     * @param classes when not complete, the sorted key hashes of the class names whose methods were all kept
     */
    public record KeptRun(
            long generation, long[] keys, int[] codes, String[] packages, boolean complete, long[] classes) {

        /** The hashes of the methods with code of a scan's classes, within {@value #MAX_PAIRS} methods. */
        public static KeptRun of(long generation, ClassScanner.Result scan) {
            List<long[]> pairs = new ArrayList<>();
            List<Long> kept = new ArrayList<>();
            boolean truncated = false;
            for (ScannedClass scanned : scan.classes().values()) {
                List<MethodHash> methods = ClassScanner.inventoried(scanned.hashes());
                if (pairs.size() + methods.size() > MAX_PAIRS) {
                    truncated = true;
                    break;
                }
                for (MethodHash method : methods) {
                    pairs.add(new long[] {ClassFileHasher.keyHash(method.key(scanned.className())), method.codeHash()});
                }
                kept.add(ClassFileHasher.keyHash(scanned.className()));
            }
            pairs.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] keys = new long[pairs.size()];
            int[] codes = new int[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                keys[i] = pairs.get(i)[0];
                codes[i] = (int) pairs.get(i)[1];
            }
            boolean complete = scan.complete() && !truncated;
            long[] classes = complete
                    ? new long[0]
                    : kept.stream().mapToLong(Long::longValue).sorted().toArray();
            return new KeptRun(generation, keys, codes, scan.packages().toArray(String[]::new), complete, classes);
        }

        /** Whether this run's scan saw the class {@code className}, present or not. */
        public boolean covers(String className) {
            if (complete) {
                return ClassScanner.inPackages(className, Arrays.asList(packages));
            }
            return Arrays.binarySearch(classes, ClassFileHasher.keyHash(className)) >= 0;
        }

        /** The code hash kept for a method key's hash, or {@code null} when this run has no such method. */
        public Integer code(long keyHash) {
            int index = Arrays.binarySearch(keys, keyHash);
            return index < 0 ? null : codes[index];
        }

        /** How many bytes its method hashes use. */
        public long bytes() {
            return 12L * keys.length;
        }
    }
}
