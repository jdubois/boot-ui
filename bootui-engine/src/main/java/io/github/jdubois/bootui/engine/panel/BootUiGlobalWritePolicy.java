package io.github.jdubois.bootui.engine.panel;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves non-panel API writes that are still governed by BootUI's global read-only policy.
 */
public final class BootUiGlobalWritePolicy {

    private static final Map<String, String> SUBJECTS_BY_PREFIX =
            Map.of("/dismissed-rules", "dismissed-rules", "/dismissed-findings", "dismissed-findings");

    private BootUiGlobalWritePolicy() {}

    public static Optional<String> subjectFor(String apiRelativePath) {
        if (apiRelativePath == null) {
            return Optional.empty();
        }
        return SUBJECTS_BY_PREFIX.entrySet().stream()
                .filter(entry ->
                        apiRelativePath.equals(entry.getKey()) || apiRelativePath.startsWith(entry.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
