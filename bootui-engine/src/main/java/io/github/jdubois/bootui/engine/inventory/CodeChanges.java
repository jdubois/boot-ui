package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import io.github.jdubois.bootui.engine.inventory.ClassScanner.ScannedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryHistory.KeptRun;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The methods changed since the previous run, without git ({@code docs/PLAN-v2.md} §5.15): a method whose key both runs
 * have with another code hash is {@link #CHANGED}, one only this run has is {@link #ADDED}, and the previous run's
 * methods this run lacks are counted as removed. Only classes both scans covered are compared, so a scan stopped at its
 * limit never reports the classes it did not reach as added or removed; the result says when it is partial.
 *
 * <p>The compiler numbers lambdas ({@code lambda$name$N}) and anonymous and local classes ({@code Outer$N}) in source
 * order, so adding one renumbers the others without changing them. A lambda or a method of such a class that would read
 * as changed or added is unchanged when the previous run had the same method under another number with the same code
 * hash, and that previous method is then not counted as removed.
 *
 * @param previousRun whether there was a previous run to compare with
 * @param kinds the changed and added methods by key, in scan order
 * @param removed how many methods the previous run had that this one lacks, or -1 when that is unknown
 * @param partial whether some classes or methods could not be compared
 */
public record CodeChanges(boolean previousRun, Map<String, String> kinds, int removed, boolean partial) {

    public static final String CHANGED = "CHANGED";
    public static final String ADDED = "ADDED";

    public CodeChanges {
        kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
    }

    /** No previous run. */
    public static CodeChanges none() {
        return new CodeChanges(false, Map.of(), 0, false);
    }

    /** Compares {@code scan} with {@code previous}. */
    public static CodeChanges diff(ClassScanner.Result scan, KeptRun previous) {
        if (previous == null) {
            return none();
        }
        Map<String, String> kinds = new LinkedHashMap<>();
        boolean partial = !scan.complete() || !previous.complete();
        List<Long> current = new ArrayList<>();
        Set<Long> renumbered = new HashSet<>();
        for (ScannedClass scanned : scan.classes().values()) {
            if (!previous.covers(scanned.className())) {
                continue;
            }
            for (MethodHash method : ClassScanner.inventoried(scanned.hashes())) {
                String key = method.key(scanned.className());
                long hash = ClassFileHasher.keyHash(key);
                current.add(hash);
                Integer code = previous.code(hash);
                String kind = code == null ? ADDED : code != method.codeHash() ? CHANGED : null;
                if (kind != null && !renumbered(previous, scanned.className(), method, renumbered)) {
                    kinds.put(key, kind);
                }
            }
        }
        int removed = -1;
        if (scan.complete() && covered(previous.packages(), scan.packages())) {
            long[] keys = current.stream().mapToLong(Long::longValue).sorted().toArray();
            removed = 0;
            for (long key : previous.keys()) {
                if (Arrays.binarySearch(keys, key) < 0 && !renumbered.contains(key)) {
                    removed++;
                }
            }
        } else {
            partial = true;
        }
        return new CodeChanges(true, kinds, removed, partial);
    }

    /** The highest compiler ordinal tried when matching a renumbered lambda or anonymous class. */
    static final int MAX_ORDINAL = 255;

    /**
     * Whether {@code method} of {@code className} is a lambda or a method of an anonymous or local class that the
     * previous run had, with the same code hash, under another compiler ordinal; its key hash is then added to
     * {@code matched}.
     */
    static boolean renumbered(KeptRun previous, String className, MethodHash method, Set<Long> matched) {
        String name = method.name();
        String descriptor = method.descriptor();
        if (name.startsWith("lambda$")) {
            int dollar = name.lastIndexOf('$');
            if (dollar > "lambda$".length() && digits(name, dollar + 1, name.length())) {
                String stem = className + "#" + name.substring(0, dollar + 1);
                for (int ordinal = 0; ordinal <= MAX_ORDINAL; ordinal++) {
                    if (matches(previous, stem + ordinal + descriptor, method.codeHash(), matched)) {
                        return true;
                    }
                }
            }
        }
        int dollar = className.lastIndexOf('$');
        if (dollar > 0) {
            int end = dollar + 1;
            while (end < className.length() && Character.isDigit(className.charAt(end))) {
                end++;
            }
            if (end > dollar + 1) {
                String prefix = className.substring(0, dollar + 1);
                String suffix = className.substring(end) + "#" + name + descriptor;
                for (int ordinal = 1; ordinal <= MAX_ORDINAL; ordinal++) {
                    if (matches(previous, prefix + ordinal + suffix, method.codeHash(), matched)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matches(KeptRun previous, String key, int codeHash, Set<Long> matched) {
        long hash = ClassFileHasher.keyHash(key);
        Integer code = previous.code(hash);
        if (code != null && code == codeHash) {
            matched.add(hash);
            return true;
        }
        return false;
    }

    private static boolean digits(String text, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether every package of {@code previous} is one of {@code current} or nested in one. */
    private static boolean covered(String[] previous, List<String> current) {
        for (String name : previous) {
            boolean found = false;
            for (String other : current) {
                if (name.equals(other) || name.startsWith(other + ".")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
