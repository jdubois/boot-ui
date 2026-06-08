package io.github.jdubois.bootui.autoconfigure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the set of dismissed advisor rule IDs stored under the
 * {@code dismissedRules} node of BootUI's generic {@code boot-ui.yml} file.
 *
 * <p>The file lives under the {@code .bootui/} directory (e.g.
 * {@code .bootui/boot-ui.yml}) and is intended to be git-ignored. It is a
 * generic BootUI configuration file that can hold other top-level sections; this
 * store only owns the {@code dismissedRules} node:</p>
 *
 * <pre>
 * # BootUI configuration (developer-local; safe to delete).
 * dismissedRules:
 *   - SPRING-001
 *   - MEM-003
 * </pre>
 *
 * <p>Any other top-level sections present in the file are preserved across
 * writes. The {@code dismissedRules} section is rewritten and emitted last, so a
 * comment placed immediately after it (with no intervening top-level key) is
 * treated as part of the managed section and not preserved.</p>
 *
 * <p>This class never uses a third-party YAML library so that it imposes no
 * additional classpath dependency on {@code bootui-autoconfigure}. It therefore
 * understands only the minimal block format it writes: a top-level
 * {@code dismissedRules:} key followed by two-space-indented {@code - <id>} list
 * items. Inline comments or quoted scalars on those items are not interpreted.</p>
 */
public class DismissedRulesStore {

    private static final String KEY = "dismissedRules";

    private static final String HEADER = "# BootUI configuration (developer-local; safe to delete).";

    private final Path file;

    public DismissedRulesStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the current set of dismissed rule IDs from disk. Returns an empty set
     * if the file does not yet exist.
     */
    public synchronized Set<String> load() {
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.exists(file)) {
            return ids;
        }
        try {
            boolean inSection = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (isTopLevelKey(line)) {
                    inSection = keyOf(line).equals(KEY);
                    continue;
                }
                if (inSection && line.startsWith("  - ")) {
                    String id = line.substring(4).trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read BootUI configuration file: " + file, e);
        }
        return ids;
    }

    /**
     * Adds a rule ID to the dismissed set and persists the updated set to disk.
     * Returns the updated set. Does nothing if the rule is already dismissed.
     */
    public synchronized Set<String> dismiss(String ruleId) {
        Set<String> ids = load();
        if (ids.add(ruleId)) {
            save(ids);
        }
        return ids;
    }

    /**
     * Removes a rule ID from the dismissed set and persists the updated set to disk.
     * Returns the updated set. Does nothing if the rule was not dismissed.
     */
    public synchronized Set<String> restore(String ruleId) {
        Set<String> ids = load();
        if (ids.remove(ruleId)) {
            save(ids);
        }
        return ids;
    }

    private void save(Set<String> ids) {
        try {
            List<String> preserved = preservedLines();
            StringBuilder sb = new StringBuilder();
            sb.append(HEADER).append('\n');
            for (String line : preserved) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
            sb.append(KEY).append(":\n");
            for (String id : ids) {
                sb.append("  - ").append(id).append('\n');
            }
            writeAtomically(sb.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write BootUI configuration file: " + file, e);
        }
    }

    /**
     * Returns the file's existing lines with the managed {@code dismissedRules}
     * section and our own header comment removed, so other top-level sections
     * survive a rewrite while the header and section are re-emitted exactly once.
     * Trailing blank lines are trimmed so the separator before the rewritten
     * section stays stable across repeated saves.
     */
    private List<String> preservedLines() throws IOException {
        List<String> preserved = new ArrayList<>();
        if (!Files.exists(file)) {
            return preserved;
        }
        boolean inSection = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.equals(HEADER)) {
                continue;
            }
            if (isTopLevelKey(line)) {
                inSection = keyOf(line).equals(KEY);
                if (!inSection) {
                    preserved.add(line);
                }
                continue;
            }
            if (!inSection) {
                preserved.add(line);
            }
        }
        while (!preserved.isEmpty() && preserved.get(preserved.size() - 1).isBlank()) {
            preserved.remove(preserved.size() - 1);
        }
        return preserved;
    }

    private void writeAtomically(String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = (file.getParent() != null)
                ? Files.createTempFile(file.getParent(), "boot-ui", ".tmp")
                : Files.createTempFile("boot-ui", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isTopLevelKey(String line) {
        if (line.isBlank()) {
            return false;
        }
        char first = line.charAt(0);
        if (Character.isWhitespace(first) || first == '#' || first == '-') {
            return false;
        }
        return line.indexOf(':') >= 0;
    }

    private static String keyOf(String line) {
        int idx = line.indexOf(':');
        return (idx >= 0 ? line.substring(0, idx) : line).trim();
    }
}
