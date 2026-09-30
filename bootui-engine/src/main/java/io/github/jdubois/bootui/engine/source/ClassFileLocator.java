package io.github.jdubois.bootui.engine.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Finds where an already-loaded class's own class file lives, during an explicit scan, without loading or
 * initializing anything.
 *
 * <p>Its class loader's resource is used when it is a plain {@code file:} or archive location. Some runtime class
 * loaders serve application classes from memory instead: Quarkus, for example, answers {@code quarkus:} for classes
 * it has transformed, such as enhanced entities, and records no code source. The locator then asks the same loader
 * for every root of the class's package, which is the view ArchUnit's package import uses, and keeps the one local
 * directory that holds the class file.</p>
 *
 * <p>The lookup is bounded to a fixed number of package roots. When the bound is reached, a root cannot be checked,
 * or more than one root holds the class, it cannot prove where the class came from, so it answers no local file and
 * {@link #notes()} says why.</p>
 */
public final class ClassFileLocator {

    static final int MAX_PACKAGE_ROOTS = 64;

    private int uncertainLookups;
    private int ambiguousLookups;

    ClassFileLocator() {}

    /** A locator for one scan. */
    public static ClassFileLocator forScan() {
        return new ClassFileLocator();
    }

    /**
     * The class file of {@code className} as {@code loader} sees it, or {@code null} when it cannot be found. A
     * class served only from memory keeps its unopenable location when its package roots cannot prove a single
     * local file.
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
            // Keep looking in the code source and the package roots.
        }
        URI fromCodeSource = fromCodeSource(type, resource);
        if (fromCodeSource != null) return fromCodeSource;
        if (fallback == null) return null;
        URI fromPackage = fromPackageRoots(loader, resource);
        return fromPackage == null ? fallback : fromPackage;
    }

    /** Why some classes served from memory could not be traced to one class file; empty otherwise. */
    public List<String> notes() {
        List<String> notes = new ArrayList<>();
        if (uncertainLookups > 0) {
            notes.add(uncertainLookups + " class(es) served from memory could not be traced to a single class file"
                    + " within the lookup bounds, so they keep no source path.");
        }
        if (ambiguousLookups > 0) {
            notes.add(ambiguousLookups + " class(es) served from memory were found in more than one location, so"
                    + " they keep no source path.");
        }
        return notes;
    }

    private URI fromPackageRoots(ClassLoader loader, String resource) {
        int slash = resource.lastIndexOf('/');
        if (slash < 0) return null;
        String packageDirectory = resource.substring(0, slash + 1);
        String fileName = resource.substring(slash + 1);
        Path match = null;
        int holders = 0;
        try {
            Enumeration<URL> roots = loader.getResources(packageDirectory);
            int seen = 0;
            while (roots.hasMoreElements()) {
                if (++seen > MAX_PACKAGE_ROOTS) {
                    uncertainLookups++;
                    return null;
                }
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    Path directory = Path.of(root.toURI());
                    Presence presence = inspect(directory, fileName);
                    if (presence == Presence.UNKNOWN) {
                        // A root that cannot be inspected could hold a second copy.
                        uncertainLookups++;
                        return null;
                    }
                    if (presence == Presence.PRESENT) {
                        holders++;
                        match = directory.resolve(fileName);
                    }
                } else if ("jar".equals(root.getProtocol())) {
                    if (archiveHolds(root, fileName)) holders++;
                } else {
                    // A root that cannot be read could hold a second copy, so it leaves the class unproven.
                    uncertainLookups++;
                    return null;
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException ex) {
            uncertainLookups++;
            return null;
        }
        if (holders > 1) {
            ambiguousLookups++;
            return null;
        }
        return match == null ? null : match.toUri();
    }

    private enum Presence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    /**
     * Whether {@code directory} holds {@code fileName} as a regular file, reading attributes without following links.
     * Anything that cannot be decided, including a link or an access failure, is {@link Presence#UNKNOWN}.
     */
    private static Presence inspect(Path directory, String fileName) {
        try {
            BasicFileAttributes root =
                    Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!root.isDirectory()) return Presence.UNKNOWN;
            BasicFileAttributes file = Files.readAttributes(
                    directory.resolve(fileName), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return file.isRegularFile() ? Presence.PRESENT : Presence.UNKNOWN;
        } catch (NoSuchFileException ex) {
            return Presence.ABSENT;
        } catch (IOException | InvalidPathException | SecurityException ex) {
            return Presence.UNKNOWN;
        }
    }

    private static boolean archiveHolds(URL packageRoot, String fileName) throws IOException {
        URLConnection connection = new URL(packageRoot, fileName).openConnection();
        connection.setUseCaches(false);
        try (InputStream ignored = connection.getInputStream()) {
            return true;
        } catch (java.io.FileNotFoundException ex) {
            return false;
        }
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
}
