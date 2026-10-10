package io.github.jdubois.bootui.engine.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded actual SQL feeder declarations; an omitted declaration makes scope completeness unknown. */
public final class SqlCaptureScopes {

    public static final int MAX_SOURCES = 64;
    public static final int MAX_NAME_LENGTH = 256;

    private final Map<String, Integer> sources = new LinkedHashMap<>();
    private boolean incomplete;

    /** Whether the scope changed, including its first incomplete declaration. No invalid name is retained. */
    public synchronized boolean register(String name, SqlPayload.Provenance provenance) {
        if (name == null
                || name.isBlank()
                || name.length() > MAX_NAME_LENGTH
                || provenance == null
                || provenance == SqlPayload.Provenance.UNKNOWN
                || (!sources.containsKey(name) && sources.size() >= MAX_SOURCES)) {
            boolean changed = !incomplete;
            incomplete = true;
            return changed;
        }
        int bit = provenance == SqlPayload.Provenance.EXECUTION ? 1 : 2;
        int previous = sources.getOrDefault(name, 0);
        sources.put(name, previous | bit);
        return (previous & bit) == 0;
    }

    public synchronized boolean coversExecution(String name) {
        return name != null && (sources.getOrDefault(name, 0) & 1) != 0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(names(1), names(2), incomplete);
    }

    private Set<String> names(int bit) {
        return sources.entrySet().stream()
                .filter(entry -> (entry.getValue() & bit) != 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Snapshot(Set<String> executions, Set<String> preparations, boolean incomplete) {
        public Snapshot {
            executions = Set.copyOf(executions);
            preparations = Set.copyOf(preparations);
        }

        /** A fixed-size signature of the whole named scope, not raw names in untrimmed summary metadata. */
        public String fingerprint(boolean execution) {
            Set<String> names = execution ? executions : preparations;
            if (names.isEmpty()) {
                return null;
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("The JDK must provide SHA-256 for SQL scope comparison", ex);
            }
            names.stream().sorted().forEach(name -> {
                byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
