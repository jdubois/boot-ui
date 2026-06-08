package io.github.jdubois.bootui.autoconfigure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the set of dismissed advisor rule IDs to a local YAML file.
 *
 * <p>The file is stored under the {@code .bootui/} directory (e.g.
 * {@code .bootui/dismissed-rules.yaml}) and is intended to be git-ignored. The
 * format is a minimal YAML list:</p>
 *
 * <pre>
 * dismissed:
 *   - SPRING-001
 *   - MEM-003
 * </pre>
 *
 * <p>This class never uses a third-party YAML library so that it imposes no
 * additional classpath dependency on {@code bootui-autoconfigure}.</p>
 */
public class DismissedRulesStore {

    private final Path file;

    public DismissedRulesStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the current set of dismissed rule IDs from disk. Returns an empty set
     * if the file does not yet exist.
     */
    public synchronized Set<String> load() {
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("  - ")) {
                    String id = line.substring(4).trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read BootUI dismissed rules file: " + file, e);
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
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder sb = new StringBuilder("dismissed:\n");
            for (String id : ids) {
                sb.append("  - ").append(id).append('\n');
            }
            Path tmp = (file.getParent() != null)
                    ? Files.createTempFile(file.getParent(), "dismissed-rules", ".tmp")
                    : Files.createTempFile("dismissed-rules", ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write BootUI dismissed rules file: " + file, e);
        }
    }
}
