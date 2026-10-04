package io.github.jdubois.bootui.engine.inventory;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The jars an application class loader can read, and the packages each holds classes in ({@code docs/PLAN-v2.md}
 * §5.15, M5-9a): runtime reach says a dependency's code did not load only when its jar was found and holds classes,
 * never for a starter, resource, or native-only jar whose use class loading cannot show, and never when a class of one
 * of its packages was defined without a code-source location. Jars are found through their
 * {@code META-INF/MANIFEST.MF}, so nested jars of a Spring Boot archive are too. Reading never throws; a jar's packages
 * are cached per location, which a jar keeps for the JVM's life.
 */
final class Archives {

    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    private static final int MAX_CACHED = 8192;

    /** The most packages kept per jar; past it, a jar is answered as holding classes in every package. */
    static final int MAX_PACKAGES = 4096;

    /** Stands for every package. */
    static final Set<String> ALL = Set.of("*");

    private static final String VERSIONS = "META-INF/versions/";

    private static final ConcurrentHashMap<String, Set<String>> PACKAGES = new ConcurrentHashMap<>();

    private Archives() {}

    /** The locations of the jars {@code loader} reads, as code-source locations ({@code file:...jar}, or nested). */
    static List<String> of(ClassLoader loader) {
        Set<String> locations = new LinkedHashSet<>();
        if (loader == null) {
            return List.of();
        }
        try {
            Enumeration<URL> manifests = loader.getResources(MANIFEST);
            while (manifests.hasMoreElements()) {
                String url = manifests.nextElement().toString();
                if (url.startsWith("jar:") && url.endsWith("!/" + MANIFEST)) {
                    String jar = url.substring(4, url.length() - MANIFEST.length() - 2);
                    // A nested jar keeps its jar: wrapper, as its classes' code source does.
                    locations.add(jar.contains("!/") || jar.startsWith("nested:") ? "jar:" + jar + "!/" : jar);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: no jar found, which only keeps reach unknown.
        }
        return new ArrayList<>(locations);
    }

    /**
     * The packages the jar at {@code location} holds class files in (a multi-release jar's versioned classes in their
     * own package), empty for a jar with none, {@link #ALL} past {@value #MAX_PACKAGES}, {@code null} when it cannot be
     * read.
     */
    static Set<String> packages(String location) {
        Set<String> cached = PACKAGES.get(location);
        if (cached != null) {
            return cached;
        }
        Set<String> packages = read(location);
        if (packages != null && PACKAGES.size() < MAX_CACHED) {
            PACKAGES.put(location, packages);
        }
        return packages;
    }

    /** Whether {@code packages}, an answer of {@link #packages}, names {@code packageName}. */
    static boolean holds(Set<String> packages, String packageName) {
        return packages == ALL || packages.contains(packageName);
    }

    private static Set<String> read(String location) {
        try {
            if (location.startsWith("file:")) {
                try (JarFile jar = new JarFile(Path.of(URI.create(location)).toFile(), false)) {
                    return classes(jar);
                }
            }
            String url = location.startsWith("jar:") ? location : "jar:" + location;
            url = url.endsWith("!/") ? url : url + "!/";
            URLConnection connection = URI.create(url).toURL().openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                // The class loader's own, cached jar: read, never closed here.
                return classes(jarConnection.getJarFile());
            }
            return null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static Set<String> classes(JarFile jar) {
        Set<String> packages = new HashSet<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.endsWith("package-info.class")) {
                continue;
            }
            if (name.startsWith(VERSIONS)) {
                int version = name.indexOf('/', VERSIONS.length());
                if (version < 0) {
                    continue;
                }
                name = name.substring(version + 1);
            } else if (name.startsWith("META-INF/")) {
                continue;
            }
            int slash = name.lastIndexOf('/');
            packages.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
            if (packages.size() > MAX_PACKAGES) {
                return ALL;
            }
        }
        return Set.copyOf(packages);
    }

    /** Forgets every answer; for tests. */
    static void clear() {
        PACKAGES.clear();
    }
}
