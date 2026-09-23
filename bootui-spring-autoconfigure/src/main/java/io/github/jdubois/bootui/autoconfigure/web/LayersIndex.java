package io.github.jdubois.bootui.autoconfigure.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed Spring Boot {@code BOOT-INF/layers.idx}, the index a layered repackaged archive carries and that
 * {@code jarmode=tools extract --layers} lays the application out by.
 *
 * <p>Spring Boot's default layering puts local (project) module libraries in the {@code application} layer and
 * every other library in {@code dependencies} or {@code snapshot-dependencies}. That is packaging evidence
 * independent of package names, so a library the index assigns elsewhere is never treated as first-party. An
 * entry belongs to the first layer, in index order, listing it exactly or listing a {@code /}-terminated
 * directory containing it, exactly as Spring Boot's own {@code IndexedLayers} resolves it.</p>
 *
 * <p>The parse is as strict as Spring Boot's: an oversized, empty, or malformed index is {@link #UNREADABLE},
 * which places no archive in the application layer, so damaged evidence never falls back to the weaker package
 * heuristic.</p>
 */
final class LayersIndex {

    static final String APPLICATION_LAYER = "application";

    /** Upper bound on the index bytes read; a larger index is treated as unreadable. */
    static final int MAX_BYTES = 1024 * 1024;

    /** An index that is present but cannot be read: no archive is placed in the application layer. */
    static final LayersIndex UNREADABLE = new LayersIndex(List.of(), true);

    private record Path(String layer, String path) {}

    private final List<Path> paths;

    private final boolean application;

    private LayersIndex(List<Path> paths, boolean application) {
        this.paths = paths;
        this.application = application;
    }

    /** Parses an index, or returns {@link #UNREADABLE} when it cannot be read, is oversized, or is malformed. */
    static LayersIndex read(InputStream input) {
        byte[] bytes;
        try {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            return UNREADABLE;
        }
        if (bytes.length > MAX_BYTES) {
            return UNREADABLE;
        }
        List<Path> paths = new ArrayList<>();
        boolean application = false;
        String layer = null;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.replace("\r", "");
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("- \"") && line.endsWith("\":") && line.length() > 5) {
                layer = line.substring(3, line.length() - 2);
                application |= APPLICATION_LAYER.equals(layer);
            } else if (layer != null && line.startsWith("  - \"") && line.endsWith("\"") && line.length() > 6) {
                paths.add(new Path(layer, line.substring(5, line.length() - 1)));
            } else {
                return UNREADABLE;
            }
        }
        return layer == null ? UNREADABLE : new LayersIndex(List.copyOf(paths), application);
    }

    /**
     * Whether {@code entryName} (for example {@code BOOT-INF/lib/orders.jar}) is in the application layer:
     * {@code null} when the index defines no {@code application} layer (a fully custom layering says nothing
     * about which libraries are the application's own), and {@code false} when no layer lists the entry.
     */
    Boolean isApplication(String entryName) {
        if (!application) {
            return null;
        }
        for (Path candidate : paths) {
            if (candidate.path().equals(entryName)
                    || (candidate.path().endsWith("/") && entryName.startsWith(candidate.path()))) {
                return APPLICATION_LAYER.equals(candidate.layer());
            }
        }
        return false;
    }
}
