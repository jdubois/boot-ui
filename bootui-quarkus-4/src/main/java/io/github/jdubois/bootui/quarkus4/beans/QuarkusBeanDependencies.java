package io.github.jdubois.bootui.quarkus4.beans;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Binary codec for the Arc injection graph captured during Quarkus augmentation.
 *
 * <p>The deployment module writes this generated resource after Arc resolves injection points. The runtime
 * provider reads it once and overlays those edges on the live CDI bean inventory.</p>
 */
public final class QuarkusBeanDependencies {

    public static final String RESOURCE_NAME = "META-INF/bootui/quarkus-bean-dependencies.bin";

    private static final int MAGIC = 0x42554942;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_STRING_BYTES = 1_048_576;

    private QuarkusBeanDependencies() {}

    public static byte[] encode(Map<String, ? extends Collection<String>> graph) {
        if (graph.size() > MAX_ENTRIES) {
            throw new IllegalStateException("Quarkus bean dependency graph exceeds the entry limit");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(bytes))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(graph.size());
                for (String name : new TreeSet<>(graph.keySet())) {
                    writeString(output, name);
                    Collection<String> values = graph.get(name);
                    TreeSet<String> dependencies = new TreeSet<>(values == null ? List.of() : values);
                    if (dependencies.size() > MAX_ENTRIES) {
                        throw new IllegalStateException("Quarkus bean dependency list exceeds the entry limit");
                    }
                    output.writeInt(dependencies.size());
                    for (String dependency : dependencies) {
                        writeString(output, dependency);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not encode the Quarkus bean dependency graph", ex);
        }
    }

    public static Map<String, List<String>> load() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream resource = classLoader == null ? null : classLoader.getResourceAsStream(RESOURCE_NAME);
        if (resource == null) {
            return Map.of();
        }
        return decode(resource);
    }

    static Map<String, List<String>> decode(byte[] bytes) {
        return decode(new ByteArrayInputStream(bytes));
    }

    private static Map<String, List<String>> decode(InputStream resource) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(resource))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalStateException("Invalid Quarkus bean dependency resource header");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported Quarkus bean dependency resource version: " + version);
            }
            int entries = readCount(input, "entry");
            Map<String, List<String>> graph = new LinkedHashMap<>(entries);
            for (int entry = 0; entry < entries; entry++) {
                String name = readString(input);
                int dependencyCount = readCount(input, "dependency");
                List<String> dependencies = new ArrayList<>(dependencyCount);
                for (int dependency = 0; dependency < dependencyCount; dependency++) {
                    dependencies.add(readString(input));
                }
                graph.put(name, List.copyOf(dependencies));
            }
            return Collections.unmodifiableMap(graph);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the Quarkus bean dependency graph", ex);
        }
    }

    private static int readCount(DataInputStream input, String kind) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalStateException("Invalid Quarkus bean dependency " + kind + " count: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalStateException("Quarkus bean dependency string exceeds the encoding limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalStateException("Invalid Quarkus bean dependency string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalStateException("Truncated Quarkus bean dependency resource");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
