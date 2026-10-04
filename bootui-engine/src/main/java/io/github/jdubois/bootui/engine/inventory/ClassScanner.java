package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.ClassHashes;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MalformedClassException;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds and hashes the application's own class files ({@code docs/PLAN-v2.md} §5.15, M5-3): every class of the claimed
 * packages in the roots that hold them, found with the application class loader's {@code getResources(packagePath)} and
 * among the {@code java.class.path} entries, directories walked and jars ({@code jar:file:} and Spring Boot's
 * {@code jar:nested:}) iterated. Test roots ({@code test-classes}, Gradle's test outputs) are excluded, as the agent
 * excludes them. Bounded by a class count and a deadline, which also bound finding the roots, and cancellable: past
 * either the result is partial and says so. Each class-path jar is opened at most once per scan, for every package at
 * once, and not again on a later restart while its path, size, and modification time are unchanged. A class file whose
 * path and size and modification time (a jar entry's CRC-32) are unchanged is not parsed again: its hashes come from
 * the {@link ScanCache} the run history keeps across restarts. A class file that cannot be parsed is skipped and
 * counted, and makes the result partial, since its methods are neither counted nor compared.
 *
 * <p>Run it on a BootUI thread marked as BootUI's work, so the classes it loads, if any, are not counted as the
 * application's.
 */
public final class ClassScanner {

    /** Code-source locations of test roots, as the agent's {@code InventorySensor.TEST_ROOTS}. */
    static final List<String> TEST_ROOTS = List.of(
            "/test-classes/",
            "/build/classes/java/test/",
            "/build/classes/kotlin/test/",
            "/build/classes/groovy/test/");

    /** A completed scan. */
    public static final String COMPLETE = "COMPLETE";

    /** A scan that stopped at its class limit or deadline: what it covered is exact, the rest is unknown. */
    public static final String PARTIAL = "PARTIAL";

    /** A scan that found nothing it could read, or failed. */
    public static final String FAILED = "FAILED";

    private ClassScanner() {}

    /**
     * One scanned class.
     *
     * @param className its binary name
     * @param root the root it was read from, as a URL string
     * @param hashes its methods' hashes
     */
    public record ScannedClass(String className, String root, ClassHashes hashes) {}

    /**
     * What a scan found.
     *
     * @param status {@link #COMPLETE}, {@link #PARTIAL}, or {@link #FAILED}
     * @param reason why it is partial or failed, or {@code null}
     * @param packages the packages it scanned
     * @param roots the roots it read, as URL strings
     * @param classes every class it hashed, by binary name
     * @param reused how many classes came from the cache
     * @param skipped how many class files could not be parsed
     * @param durationMillis how long it took
     */
    public record Result(
            String status,
            String reason,
            List<String> packages,
            List<String> roots,
            Map<String, ScannedClass> classes,
            int reused,
            int skipped,
            long durationMillis) {

        public Result {
            packages = List.copyOf(packages);
            roots = List.copyOf(roots);
            classes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        }

        /** Whether every class of its packages in its roots was hashed. */
        public boolean complete() {
            return COMPLETE.equals(status);
        }
    }

    /**
     * Scans the classes of {@code packages} that {@code loader} can see.
     *
     * @param maxClasses the most classes hashed, {@code bootui.code-inventory.max-classes}
     * @param timeout the deadline
     * @param cache hashes kept across runs by file path, size, and modification time, or {@code null}
     */
    public static Result scan(
            ClassLoader loader, List<String> packages, int maxClasses, Duration timeout, ScanCache cache) {
        return scan(loader, packages, maxClasses, timeout, cache, () -> false);
    }

    /**
     * Scans as {@link #scan(ClassLoader, List, int, Duration, ScanCache)} does, stopping early once {@code cancelled}
     * answers true, as when the run ends while it scans.
     */
    public static Result scan(
            ClassLoader loader,
            List<String> packages,
            int maxClasses,
            Duration timeout,
            ScanCache cache,
            BooleanSupplier cancelled) {
        return scan(loader, packages, maxClasses, timeout, cache, System::nanoTime, classPath(), cancelled);
    }

    static Result scan(
            ClassLoader loader,
            List<String> packages,
            int maxClasses,
            Duration timeout,
            ScanCache cache,
            LongSupplier nanoTime,
            List<String> classPath) {
        return scan(loader, packages, maxClasses, timeout, cache, nanoTime, classPath, () -> false);
    }

    static Result scan(
            ClassLoader loader,
            List<String> packages,
            int maxClasses,
            Duration timeout,
            ScanCache cache,
            LongSupplier nanoTime,
            List<String> classPath,
            BooleanSupplier cancelled) {
        long started = nanoTime.getAsLong();
        long deadline = started + (timeout == null ? Duration.ofSeconds(30) : timeout).toNanos();
        List<String> clean = clean(packages);
        Scan scan = new Scan(clean, Math.max(1, maxClasses), deadline, cache, nanoTime, cancelled);
        try {
            for (Root root : scan.roots(loader, classPath)) {
                if (scan.stopped != null) {
                    break;
                }
                scan.read(root);
            }
        } catch (RuntimeException ex) {
            scan.stop("The scan failed: " + ex.getClass().getSimpleName() + ".");
        }
        long millis = (nanoTime.getAsLong() - started) / 1_000_000L;
        String status;
        String reason = scan.stopped;
        if (clean.isEmpty()) {
            status = FAILED;
            reason = "No application package is claimed, so no class file was scanned.";
        } else if (scan.classes.isEmpty() && scan.stopped == null) {
            status = scan.roots.isEmpty() ? FAILED : COMPLETE;
            if (scan.roots.isEmpty()) {
                reason = "No class directory or jar holding " + String.join(", ", clean)
                        + " was found on the application's class path.";
            }
        } else if (scan.stopped == null && scan.skipped > 0) {
            // A class file it could not parse is neither counted nor compared: not a complete picture of its packages.
            status = PARTIAL;
            reason = scan.skipped + (scan.skipped == 1 ? " class file" : " class files")
                    + " could not be parsed: their methods are neither counted nor compared with the previous run.";
        } else {
            status = scan.stopped == null ? COMPLETE : PARTIAL;
        }
        return new Result(
                status, reason, clean, new ArrayList<>(scan.roots), scan.classes, scan.reused, scan.skipped, millis);
    }

    /** Whether a code-source location is a test root, whose classes are neither instrumented nor scanned. */
    public static boolean testRoot(String location) {
        if (location == null) {
            return false;
        }
        String normalized = location.replace('\\', '/');
        normalized = normalized.endsWith("/") ? normalized : normalized + "/";
        for (String root : TEST_ROOTS) {
            if (normalized.contains(root)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code className} is in one of {@code packages}, as the agent matches claimed classes. */
    public static boolean inPackages(String className, List<String> packages) {
        for (String prefix : packages) {
            if (className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> classPath() {
        String value = System.getProperty("java.class.path");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static List<String> clean(List<String> packages) {
        Set<String> names = new LinkedHashSet<>();
        if (packages != null) {
            for (String name : packages) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        // A package nested in another claimed one is already covered by it.
        List<String> covering = new ArrayList<>();
        for (String name : names) {
            boolean nested = false;
            for (String other : names) {
                if (!other.equals(name) && name.startsWith(other + ".")) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                covering.add(name);
            }
        }
        return covering;
    }

    // ---- roots ---------------------------------------------------------------------------------------------------

    /** A directory or a jar holding some claimed packages. */
    private record Root(String location, Path directory, URL jar, Path jarFile) {}

    /** The root of a package resource URL: the directory or jar above {@code path}. */
    private static Root root(URL url, String path) {
        String text = url.toString();
        String protocol = url.getProtocol();
        try {
            if ("file".equals(protocol)) {
                Path directory = Path.of(url.toURI());
                Path root = directory;
                for (int i = 0; i < path.split("/").length && root != null; i++) {
                    root = root.getParent();
                }
                return root == null ? null : new Root(root.toUri().toString(), root, null, null);
            }
            if ("jar".equals(protocol)) {
                int separator = text.lastIndexOf("!/");
                if (separator < 0) {
                    return null;
                }
                String jar = text.substring(0, separator + 2);
                String inner = jar.substring("jar:".length(), jar.length() - 2);
                if (inner.startsWith("file:") && !inner.contains("!/")) {
                    return new Root(jar, null, null, Path.of(URI.create(inner)));
                }
                return new Root(jar, null, URI.create(jar).toURL(), null);
            }
        } catch (Exception ex) {
            // An unusual URL: not a root this scan can read.
        }
        return null;
    }

    /**
     * A class-path entry holding some of {@code paths} (package paths, such as {@code com/example}): a directory holding
     * one, or a jar with an entry under one, read once for all of them, and remembered in {@code cache} by path, size,
     * and modification time. {@code null} for neither.
     */
    private static Root classPathRoot(String entry, List<String> paths, ScanCache cache) {
        try {
            Path file = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isDirectory(file)) {
                for (String path : paths) {
                    if (Files.isDirectory(file.resolve(path))) {
                        return new Root(file.toUri().toString(), file, null, null);
                    }
                }
                return null;
            }
            if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar")) {
                String location = "jar:" + file.toUri() + "!/";
                if (testRoot(location)) {
                    return null;
                }
                String key = file + "|" + Files.size(file) + "|"
                        + Files.getLastModifiedTime(file).toMillis() + "|" + String.join(",", paths);
                Boolean known = cache == null ? null : cache.jarHolds(key);
                boolean holds;
                if (known != null) {
                    holds = known;
                } else {
                    holds = jarHolds(file, paths);
                    if (cache != null) {
                        cache.jarHolds(key, holds);
                    }
                }
                return holds ? new Root(location, null, null, file) : null;
            }
        } catch (IOException | InvalidPathException | SecurityException ex) {
            // Unreadable: skipped.
        }
        return null;
    }

    /** Whether the jar has an entry under one of {@code paths}: one pass over its entries, stopping at the first. */
    private static boolean jarHolds(Path file, List<String> paths) throws IOException {
        List<String> prefixes = paths.stream().map(path -> path + "/").toList();
        try (JarFile jar = new JarFile(file.toFile(), false)) {
            for (String prefix : prefixes) {
                if (jar.getEntry(prefix) != null) {
                    return true;
                }
            }
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private static final class Scan {

        private final List<String> packages;
        private final int maxClasses;
        private final long deadline;
        private final ScanCache cache;
        private final LongSupplier nanoTime;
        private final BooleanSupplier cancelled;
        private final Map<String, ScannedClass> classes = new LinkedHashMap<>();
        private final Set<String> roots = new LinkedHashSet<>();
        private int reused;
        private int skipped;
        private String stopped;

        Scan(
                List<String> packages,
                int maxClasses,
                long deadline,
                ScanCache cache,
                LongSupplier nanoTime,
                BooleanSupplier cancelled) {
            this.packages = packages;
            this.maxClasses = maxClasses;
            this.deadline = deadline;
            this.cache = cache;
            this.nanoTime = nanoTime;
            this.cancelled = cancelled;
        }

        void stop(String reason) {
            if (stopped == null) {
                stopped = reason;
            }
        }

        /** Whether the scan must stop now, cancelled or past its deadline, saying why once. */
        boolean interrupted() {
            if (stopped != null) {
                return true;
            }
            if (cancelled.getAsBoolean()) {
                stop("The scan was cancelled: the run ended while it scanned.");
                return true;
            }
            if (nanoTime.getAsLong() - deadline > 0) {
                stop("The scan stopped at its deadline, bootui.code-inventory.scan-timeout, after " + classes.size()
                        + " classes.");
                return true;
            }
            return false;
        }

        /**
         * The roots holding the claimed packages: those the class loader names for each package path, then the class
         * path's directories and jars, each jar read at most once, for every package at once. Stops, keeping what it
         * found, once cancelled or past the deadline.
         */
        List<Root> roots(ClassLoader loader, List<String> classPath) {
            Map<String, Root> found = new LinkedHashMap<>();
            List<String> paths =
                    packages.stream().map(name -> name.replace('.', '/')).toList();
            if (loader != null) {
                for (String path : paths) {
                    if (interrupted()) {
                        return new ArrayList<>(found.values());
                    }
                    try {
                        Enumeration<URL> urls = loader.getResources(path);
                        while (urls.hasMoreElements()) {
                            Root root = root(urls.nextElement(), path);
                            if (root != null && !testRoot(root.location())) {
                                found.putIfAbsent(root.location(), root);
                            }
                        }
                    } catch (IOException | RuntimeException ex) {
                        // A loader that cannot list resources: the class path below may still find the roots.
                    }
                }
            }
            for (String entry : classPath) {
                if (interrupted()) {
                    break;
                }
                Root root = classPathRoot(entry, paths, cache);
                if (root != null && !testRoot(root.location())) {
                    found.putIfAbsent(root.location(), root);
                }
            }
            return new ArrayList<>(found.values());
        }

        void read(Root root) {
            roots.add(root.location());
            if (root.directory() != null) {
                readDirectory(root);
            } else if (root.jarFile() != null) {
                try (JarFile jar = new JarFile(root.jarFile().toFile(), false)) {
                    readJar(root, jar);
                } catch (IOException ex) {
                    // An unreadable jar: nothing scanned from it.
                }
            } else if (root.jar() != null) {
                try {
                    URLConnection connection = root.jar().openConnection();
                    if (connection instanceof JarURLConnection jarConnection) {
                        // The class loader's own, cached jar: read, never closed here.
                        readJar(root, jarConnection.getJarFile());
                    }
                } catch (IOException | RuntimeException ex) {
                    // A nested jar this scan cannot open.
                }
            }
        }

        private void readDirectory(Root root) {
            for (String name : packages) {
                Path start = root.directory().resolve(name.replace('.', '/'));
                if (!Files.isDirectory(start)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(start)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (interrupted()) {
                            return;
                        }
                        String fileName = file.getFileName().toString();
                        if (!fileName.endsWith(".class") || !Files.isRegularFile(file)) {
                            continue;
                        }
                        String relative =
                                root.directory().relativize(file).toString().replace(File.separatorChar, '/');
                        long size = Files.size(file);
                        long modified = Files.getLastModifiedTime(file).toMillis();
                        accept(root, relative, file.toUri().toString(), size, modified, () -> Files.readAllBytes(file));
                    }
                } catch (IOException | RuntimeException ex) {
                    // A directory that vanished while it was walked, as during a rebuild: what was read stays.
                }
            }
        }

        private void readJar(Root root, JarFile jar) {
            List<String> prefixes =
                    packages.stream().map(name -> name.replace('.', '/') + "/").toList();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements() && !interrupted()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.endsWith(".class")
                        || prefixes.stream().noneMatch(name::startsWith)) {
                    continue;
                }
                // A jar entry's CRC-32 says whether its bytes changed; its DOS time, two-second precise, may not.
                long crc = entry.getCrc();
                long stamp = crc >= 0 ? crc : entry.getTime();
                accept(root, name, root.location() + name, entry.getSize(), stamp, () -> {
                    try (InputStream in = jar.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                });
            }
        }

        private void accept(Root root, String relative, String key, long size, long stamp, Bytes bytes) {
            if (interrupted()) {
                return;
            }
            String simple = relative.substring(relative.lastIndexOf('/') + 1);
            if (simple.equals("module-info.class") || simple.equals("package-info.class")) {
                return;
            }
            String expected =
                    relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
            if (classes.containsKey(expected)) {
                // An earlier root on the class path holds it: the class loader would load that one.
                return;
            }
            if (classes.size() >= maxClasses) {
                stop("The scan stopped at bootui.code-inventory.max-classes, " + maxClasses + " classes.");
                return;
            }
            ClassHashes hashes = cache == null ? null : cache.get(key, size, stamp);
            if (hashes != null) {
                reused++;
            } else {
                try {
                    hashes = ClassFileHasher.hash(bytes.read());
                } catch (MalformedClassException | IOException ex) {
                    skipped++;
                    return;
                }
                if (cache != null) {
                    cache.put(key, size, stamp, hashes);
                }
            }
            if (!hashes.className().equals(expected) || !inPackages(hashes.className(), packages)) {
                return;
            }
            classes.put(hashes.className(), new ScannedClass(hashes.className(), root.location(), hashes));
        }
    }

    @FunctionalInterface
    private interface Bytes {
        byte[] read() throws IOException;
    }

    /** The methods of a scanned class that Code Inventory lists: those with code, and abstract ones. */
    static List<MethodHash> inventoried(ClassHashes hashes) {
        return hashes.methods().stream().filter(MethodHash::inventoried).toList();
    }
}
