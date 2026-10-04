package io.github.jdubois.bootui.engine.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Who a code source is ({@code docs/PLAN-v2.md} §5.15): the jar file name and the Maven coordinates read from its
 * {@code META-INF/maven/<group>/<artifact>/pom.properties}, so a declared dependency is matched to the jar its classes
 * came from by {@code groupId:artifactId}, falling back to the file name. Handles {@code file:} jars and directories,
 * and Spring Boot's {@code jar:nested:} jars through the URL's own handler. Identities are cached per location, which
 * a code source keeps for the JVM's life; reading one never throws.
 */
final class CodeSources {

    /** The Maven group of BootUI's own artifacts, whose code sources are not application dependencies. */
    static final String BOOTUI_GROUP = "com.julien-dubois.bootui";

    private static final int MAX_CACHED = 8192;

    /** The most {@code pom.properties} read from one jar. */
    static final int MAX_METADATA = 64;

    private static final ConcurrentHashMap<String, Identity> CACHE = new ConcurrentHashMap<>();

    private CodeSources() {}

    /**
     * A code source's identity.
     *
     * @param location its location, as the agent recorded it
     * @param fileName its file name, such as {@code commons-lang3-3.17.0.jar}, or the directory's
     * @param directory whether it is a class directory rather than a jar
     * @param groupId its Maven group, or {@code null}
     * @param artifactId its Maven artifact, or {@code null}
     * @param version its version, or {@code null}
     * @param coordinates every {@code groupId:artifactId} its Maven metadata names (a shaded jar names several)
     * @param coordinatesTruncated whether it names more than {@value #MAX_METADATA}, so {@code coordinates} is partial
     */
    record Identity(
            String location,
            String fileName,
            boolean directory,
            String groupId,
            String artifactId,
            String version,
            List<String> coordinates,
            boolean coordinatesTruncated) {

        /** Whether it is one of BootUI's own artifacts or the BootUI agent. */
        boolean bootUi() {
            return BOOTUI_GROUP.equals(groupId)
                    || coordinates.stream().anyMatch(c -> c.startsWith(BOOTUI_GROUP + ":"))
                    || (fileName != null && fileName.startsWith("bootui-") && fileName.endsWith(".jar"));
        }
    }

    /** The identity of the code source at {@code location}. */
    static Identity of(String location) {
        Identity cached = CACHE.get(location);
        if (cached != null) {
            return cached;
        }
        Identity identity = read(location);
        if (CACHE.size() < MAX_CACHED) {
            CACHE.put(location, identity);
        }
        return identity;
    }

    private static Identity read(String location) {
        String path = location;
        int nested = path.lastIndexOf("!/");
        if (nested >= 0) {
            path = path.substring(0, nested);
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String fileName = decode(trimmed.substring(trimmed.lastIndexOf('/') + 1));
        boolean directory = location.endsWith("/") && !location.endsWith("!/");
        List<String[]> metadata = new ArrayList<>();
        try {
            if (directory && location.startsWith("file:")) {
                fileName = directoryName(Path.of(URI.create(location)));
            } else if (location.startsWith("file:")) {
                try (JarFile jar = new JarFile(Path.of(URI.create(location)).toFile(), false)) {
                    metadata = metadata(jar);
                }
            } else if (location.startsWith("jar:") || location.startsWith("nested:")) {
                String url = location.startsWith("jar:") ? location : "jar:" + location;
                url = url.endsWith("!/") ? url : url + "!/";
                URLConnection connection = URI.create(url).toURL().openConnection();
                if (connection instanceof JarURLConnection jarConnection) {
                    // The class loader's own, cached jar: read, never closed here.
                    metadata = metadata(jarConnection.getJarFile());
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: identified by its file name only.
        }
        boolean truncated = metadata.size() > MAX_METADATA;
        if (truncated) {
            metadata = metadata.subList(0, MAX_METADATA);
        }
        String[] main = pick(metadata, fileName);
        List<String> coordinates = new ArrayList<>();
        for (String[] gav : metadata) {
            coordinates.add(gav[0] + ":" + gav[1]);
        }
        return new Identity(
                location,
                fileName,
                directory,
                main == null ? null : main[0],
                main == null ? null : main[1],
                main == null ? null : main[2],
                List.copyOf(coordinates),
                truncated);
    }

    /** A class directory's name, such as {@code target/classes}, so a reader can tell modules apart. */
    private static String directoryName(Path directory) {
        Path parent = directory.getParent();
        return parent == null || parent.getFileName() == null
                ? String.valueOf(directory.getFileName())
                : parent.getFileName() + "/" + directory.getFileName();
    }

    private static List<String[]> metadata(JarFile jar) {
        List<String[]> found = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements() && found.size() <= MAX_METADATA) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                Properties properties = new Properties();
                try (InputStream in = jar.getInputStream(entry)) {
                    properties.load(in);
                } catch (IOException | RuntimeException ex) {
                    continue;
                }
                String group = properties.getProperty("groupId");
                String artifact = properties.getProperty("artifactId");
                if (group != null && artifact != null) {
                    found.add(new String[] {group.trim(), artifact.trim(), trim(properties.getProperty("version"))});
                }
            }
        }
        return found;
    }

    /** The coordinates naming the jar itself: the one whose artifact starts its file name, else a lone one. */
    private static String[] pick(List<String[]> metadata, String fileName) {
        if (metadata.isEmpty()) {
            return null;
        }
        for (String[] gav : metadata) {
            if (fileName != null && (fileName.startsWith(gav[1] + "-") || fileName.startsWith(gav[0] + "." + gav[1]))) {
                return gav;
            }
        }
        return metadata.size() == 1 ? metadata.get(0) : null;
    }

    /** Whether {@code fileName} is the jar of {@code artifactId} at {@code version}, as Maven and Quarkus name it. */
    static boolean fileNameMatches(String fileName, String groupId, String artifactId, String version) {
        if (fileName == null || artifactId == null) {
            return false;
        }
        String withVersion = version == null || version.isBlank() ? null : artifactId + "-" + version + ".jar";
        if (withVersion != null) {
            return fileName.equals(withVersion)
                    || (groupId != null && fileName.equals(groupId + "." + withVersion))
                    || fileName.startsWith(artifactId + "-" + version + "-");
        }
        return fileName.startsWith(artifactId + "-") && fileName.endsWith(".jar");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    /** Forgets every identity; for tests. */
    static void clear() {
        CACHE.clear();
    }
}
