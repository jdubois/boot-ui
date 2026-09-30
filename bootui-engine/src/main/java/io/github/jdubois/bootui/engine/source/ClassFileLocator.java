package io.github.jdubois.bootui.engine.source;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Finds where an already-loaded class's own class file lives, during an explicit scan, without loading or
 * initializing anything.
 *
 * <p>Its class loader's resource is used when it is a plain {@code file:} or archive location. Some runtime class
 * loaders serve application classes from an unopenable scheme instead: Quarkus, for example, answers
 * {@code quarkus:} for classes it has transformed, such as enhanced entities, and records no code source. The
 * locator then looks for the same relative file in the directories of the launch class path, including those
 * named by a launcher jar's manifest {@code Class-Path} (as Surefire and the Quarkus dev launcher use), within a
 * small fixed budget.</p>
 */
public final class ClassFileLocator {

    private static final int MAX_CLASS_PATH_ENTRIES = 4096;
    private static final int MAX_MANIFESTS = 32;

    private final java.util.function.Supplier<List<Path>> classPath;
    private List<Path> directories;

    ClassFileLocator(java.util.function.Supplier<List<Path>> classPath) {
        this.classPath = classPath;
    }

    /**
     * A locator for one scan. The launch class path's directories are read at most once, and only when a class
     * loader does not already answer with a readable location.
     */
    public static ClassFileLocator forScan() {
        return new ClassFileLocator(() -> classPathDirectories(System.getProperty("java.class.path", "")));
    }

    /**
     * The class file of {@code className} as {@code loader} sees it, or {@code null} when it cannot be found.
     *
     * @param type the loaded class when known, whose code source may name its output directory
     */
    public URI locate(String className, ClassLoader loader, Class<?> type) {
        if (className == null || loader == null) return null;
        String resource = className.replace('.', '/') + ".class";
        URI fallback = null;
        try {
            URL url = loader.getResource(resource);
            if (url != null) {
                URI uri = url.toURI();
                if ("file".equals(url.getProtocol()) || "jar".equals(url.getProtocol())) return uri;
                fallback = uri;
            }
        } catch (URISyntaxException | RuntimeException ex) {
            // Keep looking in the code source and the class path.
        }
        URI fromCodeSource = fromCodeSource(type, resource);
        if (fromCodeSource != null) return fromCodeSource;
        if (directories == null) directories = List.copyOf(classPath.get());
        Path match = null;
        for (Path directory : directories) {
            Path file = regularFile(directory, resource);
            if (file == null) continue;
            // Two output directories with the same class cannot say which one was loaded.
            if (match != null) return fallback;
            match = file;
        }
        return match == null ? fallback : match.toUri();
    }

    private static URI fromCodeSource(Class<?> type, String resource) {
        try {
            if (type == null || type.getProtectionDomain() == null) return null;
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null
                    || source.getLocation() == null
                    || !"file".equals(source.getLocation().getProtocol())) {
                return null;
            }
            Path file = regularFile(Path.of(source.getLocation().toURI()), resource);
            return file == null ? null : file.toUri();
        } catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }

    private static Path regularFile(Path directory, String resource) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
            Path file = directory.resolve(resource).normalize();
            return file.startsWith(directory) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? file : null;
        } catch (InvalidPathException | SecurityException ex) {
            return null;
        }
    }

    static List<Path> classPathDirectories(String classPath) {
        Set<Path> directories = new LinkedHashSet<>();
        List<Path> jars = new ArrayList<>();
        String[] entries = classPath.split(java.io.File.pathSeparator);
        for (int index = 0; index < entries.length && index < MAX_CLASS_PATH_ENTRIES; index++) {
            if (entries[index].isBlank()) continue;
            try {
                Path entry = Path.of(entries[index]).toAbsolutePath().normalize();
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) directories.add(entry);
                else if (entry.getFileName() != null
                        && entry.getFileName().toString().endsWith(".jar")) jars.add(entry);
            } catch (InvalidPathException | SecurityException ex) {
                // An unreadable entry cannot hold a local output directory.
            }
        }
        for (int index = 0; index < jars.size() && index < MAX_MANIFESTS; index++) {
            directories.addAll(manifestDirectories(jars.get(index)));
        }
        return List.copyOf(directories);
    }

    private static List<Path> manifestDirectories(Path jar) {
        List<Path> directories = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null) return directories;
            String classPath = file.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null) return directories;
            String[] entries = classPath.trim().split("\\s+");
            for (int index = 0; index < entries.length && index < MAX_CLASS_PATH_ENTRIES; index++) {
                Path entry = manifestEntry(jar, entries[index]);
                if (entry != null && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) directories.add(entry);
            }
        } catch (IOException | RuntimeException ex) {
            // A launcher jar that cannot be read names no directory.
        }
        return directories;
    }

    private static Path manifestEntry(Path jar, String entry) {
        try {
            URI uri = new URI(entry);
            if (uri.isAbsolute()) {
                return "file".equals(uri.getScheme()) ? Path.of(uri).normalize() : null;
            }
            Path parent = jar.getParent();
            return parent == null ? null : Path.of(parent.toUri().resolve(uri)).normalize();
        } catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }
}
