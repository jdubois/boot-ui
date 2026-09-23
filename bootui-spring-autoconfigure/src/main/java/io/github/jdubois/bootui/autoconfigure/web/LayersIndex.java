package io.github.jdubois.bootui.autoconfigure.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed Spring Boot {@code BOOT-INF/layers.idx}, the index a layered repackaged archive carries and that
 * {@code jarmode=tools extract --layers} lays the application out by.
 *
 * <p>Spring Boot's default layering puts local (project) module libraries in the {@code application} layer and
 * every other library in {@code dependencies} or {@code snapshot-dependencies}. That is packaging evidence
 * independent of package names, so a library assigned elsewhere is never treated as first-party. The index lists
 * each layer's files and directories ({@code /}-terminated); an entry belongs to its exact file, else to its
 * longest listed directory.</p>
 */
final class LayersIndex {

    static final String APPLICATION_LAYER = "application";

    /** Upper bound on the index bytes read; a larger index is ignored rather than parsed. */
    static final int MAX_BYTES = 1024 * 1024;

    private final Map<String, String> layerByPath;

    private LayersIndex(Map<String, String> layerByPath) {
        this.layerByPath = layerByPath;
    }

    /**
     * Parses an index, or returns {@code null} when it is oversized or lists no {@code application} layer (a fully
     * custom layering says nothing about which libraries are the application's own).
     */
    static LayersIndex read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            return null;
        }
        Map<String, String> layerByPath = new LinkedHashMap<>();
        boolean application = false;
        String layer = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String value = quoted(line);
                if (value == null) {
                    continue;
                }
                if (line.startsWith("- ") && line.stripTrailing().endsWith(":")) {
                    layer = value;
                    application |= APPLICATION_LAYER.equals(layer);
                } else if (layer != null && line.startsWith("  - ")) {
                    layerByPath.putIfAbsent(value, layer);
                }
            }
        }
        return application ? new LayersIndex(Map.copyOf(layerByPath)) : null;
    }

    /** Whether {@code entryName} (for example {@code BOOT-INF/lib/orders.jar}) is in the application layer. */
    boolean isApplication(String entryName) {
        String layer = layerByPath.get(entryName);
        if (layer == null) {
            String longest = null;
            for (String path : layerByPath.keySet()) {
                if (path.endsWith("/")
                        && entryName.startsWith(path)
                        && (longest == null || path.length() > longest.length())) {
                    longest = path;
                }
            }
            layer = longest == null ? null : layerByPath.get(longest);
        }
        return APPLICATION_LAYER.equals(layer);
    }

    private static String quoted(String line) {
        int start = line.indexOf('"');
        int end = line.lastIndexOf('"');
        return start < 0 || end <= start ? null : line.substring(start + 1, end);
    }
}
