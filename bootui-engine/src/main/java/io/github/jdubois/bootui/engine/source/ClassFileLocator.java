package io.github.jdubois.bootui.engine.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Finds where an already-loaded class's own class file lives, during an explicit scan, without loading or
 * initializing anything.
 *
 * <p>Its class loader's resource is used when it is a plain {@code file:} or archive location. Some runtime class
 * loaders serve application classes from an unopenable scheme instead: Quarkus, for example, answers
 * {@code quarkus:} for classes it has transformed, such as enhanced entities, and records no code source. The
 * locator then looks for the same relative file in the directories of the launch class path, including those
 * named by a launcher jar's manifest {@code Class-Path} (as Surefire and the Quarkus dev launcher use).</p>
 *
 * <p>That search is bounded: a capped number of class path entries and jars, only manifest-only launcher jars read,
 * each within a capped size. When any bound is reached, or two directories hold the class, the search cannot prove
 * where the class came from, so it answers no local file and {@link #notes()} says why.</p>
 */
public final class ClassFileLocator {

    static final int MAX_CLASS_PATH_ENTRIES = 4096;
    static final int MAX_JARS = 1024;
    static final int MAX_LAUNCHER_JARS = 8;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final long MAX_LAUNCHER_JAR_BYTES = 1024L * 1024;

    /**
     * The directories of a launch class path.
     *
     * @param complete whether every entry that could name a directory was read within the bounds
     */
    record ClassPath(List<Path> directories, boolean complete) {
        ClassPath {
            directories = List.copyOf(directories);
        }
    }

    private final Supplier<ClassPath> classPath;
    private ClassPath directories;
    private int incompleteLookups;
    private int ambiguousLookups;

    ClassFileLocator(Supplier<ClassPath> classPath) {
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
     * The class file of {@code className} as {@code loader} sees it, or {@code null} when it cannot be found. A
     * class served only from memory keeps its unopenable location when the class path cannot prove a single file.
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
        if (directories == null) directories = classPath.get();
        Path match = null;
        for (Path directory : directories.directories()) {
            Path file = regularFile(directory, resource);
            if (file == null) continue;
            if (match != null) {
                // Two output directories with the same class cannot say which one was loaded.
                ambiguousLookups++;
                return fallback;
            }
            match = file;
        }
        if (!directories.complete()) {
            // An unread entry could hold a second copy, so even a single match is not proof.
            if (fallback != null || match != null) incompleteLookups++;
            return fallback;
        }
        return match == null ? fallback : match.toUri();
    }

    /** Why some classes served from memory could not be traced to one class file; empty otherwise. */
    public List<String> notes() {
        List<String> notes = new ArrayList<>();
        if (incompleteLookups > 0) {
            notes.add("The launch class path was too large to search completely; " + incompleteLookups
                    + " class(es) served from memory keep no source path.");
        }
        if (ambiguousLookups > 0) {
            notes.add(ambiguousLookups + " class(es) served from memory were found in more than one class path"
                    + " directory, so they keep no source path.");
        }
        return notes;
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

    static ClassPath classPathDirectories(String classPath) {
        Set<Path> directories = new LinkedHashSet<>();
        List<Path> jars = new ArrayList<>();
        boolean complete = true;
        String[] entries = classPath.split(java.io.File.pathSeparator);
        if (entries.length > MAX_CLASS_PATH_ENTRIES) complete = false;
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
        // Only a launcher jar (Surefire's booter, the Quarkus dev launcher), which holds nothing but its manifest,
        // names output directories. Directories listed directly are authoritative, and dependency jars are never
        // read beyond their central directory. More jars than the bound leave the search unproven.
        if (jars.size() > MAX_JARS) {
            complete = false;
        } else {
            int launchers = 0;
            for (Path jar : jars) {
                Launcher launcher = launcher(jar);
                if (launcher == Launcher.NO) continue;
                if (launcher == Launcher.UNKNOWN || ++launchers > MAX_LAUNCHER_JARS) {
                    complete = false;
                    continue;
                }
                complete &= manifestDirectories(jar, directories);
            }
        }
        return new ClassPath(new ArrayList<>(directories), complete);
    }

    private enum Launcher {
        YES,
        NO,
        UNKNOWN
    }

    /** Whether {@code jar} is a manifest-only launcher jar, judged from its central directory alone. */
    private static Launcher launcher(Path jar) {
        try {
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) return Launcher.NO;
            if (Files.size(jar) > MAX_LAUNCHER_JAR_BYTES) return Launcher.NO;
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                if (file.size() > 2) return Launcher.NO;
                return file.getJarEntry(JarFile.MANIFEST_NAME) == null ? Launcher.NO : Launcher.YES;
            }
        } catch (IOException | RuntimeException ex) {
            return Launcher.UNKNOWN;
        }
    }

    /** Adds the directories {@code jar}'s manifest names; returns whether the manifest was read within bounds. */
    private static boolean manifestDirectories(Path jar, Set<Path> directories) {
        try {
            byte[] bytes;
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                JarEntry entry = file.getJarEntry(JarFile.MANIFEST_NAME);
                if (entry == null) return true;
                if (entry.getSize() > MAX_MANIFEST_BYTES) return false;
                try (InputStream input = file.getInputStream(entry)) {
                    bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                }
            }
            if (bytes.length > MAX_MANIFEST_BYTES) return false;
            String classPath = new Manifest(new ByteArrayInputStream(bytes))
                    .getMainAttributes()
                    .getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.isBlank()) return true;
            String[] entries = classPath.trim().split("\\s+");
            if (entries.length > MAX_CLASS_PATH_ENTRIES) return false;
            for (String value : entries) {
                Path entry = manifestEntry(jar, value);
                if (entry != null && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) directories.add(entry);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            // An unreadable launcher jar may have named a directory, so the search is not complete.
            return false;
        }
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
