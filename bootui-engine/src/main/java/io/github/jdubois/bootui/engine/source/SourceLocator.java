package io.github.jdubois.bootui.engine.source;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves classes compiled into a local Maven or Gradle output directory to exactly one source file, during
 * an explicit scan only. It reuses the Architecture advisor's module and source-set derivation
 * ({@link LocalSourceModule}) and its budgeted, symlink-refusing tree access ({@link SourceTreeReader}).
 *
 * <p>A class keeps no path, and the result explains why in a bounded note, when it was read from an
 * archive, compiled into any other layout, recorded no source file name, matched no file or more than one,
 * or when the lookup budget ran out before uniqueness could be proven. The locator reads only candidate
 * files whose name matches a recorded source file, to learn their package and length; it never follows a
 * link and never looks outside the module's source-set and generated-source roots.</p>
 */
public final class SourceLocator {

    public static final Limits DEFAULT_LIMITS = new Limits(64, 50_000, 32, 256 * 1024, 16 * 1024 * 1024);

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".gradle", ".m2", "node_modules");
    private static final Set<String> NON_SOURCE_DIRECTORIES = Set.of("resources", "webapp", "frontend");

    /** Budgets for one lookup; each must be positive. */
    public record Limits(int modules, int entries, int depth, int fileBytes, int totalBytes) {
        public Limits {
            if (modules < 1 || entries < 1 || depth < 1 || fileBytes < 1 || totalBytes < 1) {
                throw new IllegalArgumentException("Source lookup limits must be positive.");
            }
        }
    }

    /**
     * One class to resolve.
     *
     * @param className the JVM binary name
     * @param classFile where the class file was loaded from, or {@code null} when unknown
     * @param sourceFile the source file name recorded in the class file, or {@code null}
     */
    public record Request(String className, URI classFile, String sourceFile) {}

    /**
     * The one source file a class resolved to.
     *
     * @param path the absolute, normalized path
     * @param lineCount how many lines the file holds, so a line beyond it can be discarded
     */
    public record Resolved(Path path, int lineCount) {}

    /**
     * @param resolved the classes that resolved to exactly one file, by binary name
     * @param notes why other classes keep no path, bounded and without paths
     */
    public record Result(Map<String, Resolved> resolved, List<String> notes) {
        public Result {
            resolved = Map.copyOf(resolved);
            notes = List.copyOf(notes);
        }

        public Optional<Resolved> get(String className) {
            return Optional.ofNullable(resolved.get(className));
        }
    }

    private enum Reason {
        ARCHIVE("%d class(es) were loaded from an archive, so they have no local source path."),
        LAYOUT("%d class(es) were not compiled into a local Maven or Gradle output directory, so they have no"
                + " source path."),
        NO_SOURCE_FILE("%d class(es) recorded no Java or Kotlin source file name, so they have no source path."),
        NOT_FOUND("%d class(es) matched no file in their module's source-set or generated-source roots."),
        AMBIGUOUS("%d class(es) matched more than one source file, so no source path is shown for them."),
        UNREADABLE("%d class(es) had a candidate source file that could not be read or parsed, so no source path"
                + " is shown for them."),
        UNTRUSTED_OUTPUT("%d class(es) have a class file that is missing or reached through a symbolic link, so"
                + " their module is not proven and they have no source path."),
        BUDGET("The bounded source lookup ran out of budget; %d class(es) keep no source path."),
        SYMBOLIC_LINK("%d class(es) keep no source path because their module's source tree contains a symbolic"
                + " link, which the lookup never follows."),
        FAILED("The source lookup failed in a module (%s); %d class(es) keep no source path.");

        private final String format;

        Reason(String format) {
            this.format = format;
        }
    }

    private record Key(String packageName, String fileName) {}

    private final Limits limits;
    private final Map<String, Resolved> resolved = new LinkedHashMap<>();
    private final Map<Reason, Set<String>> unresolved = new TreeMap<>();
    private final Map<String, Set<String>> failures = new TreeMap<>();
    private final Map<String, Path> classFiles = new LinkedHashMap<>();

    private SourceLocator(Limits limits) {
        this.limits = limits;
    }

    public static Result resolve(Collection<Request> requests) {
        return resolve(requests, DEFAULT_LIMITS);
    }

    public static Result resolve(Collection<Request> requests, Limits limits) {
        return new SourceLocator(limits).run(requests);
    }

    private Result run(Collection<Request> requests) {
        Map<LocalSourceModule, Map<Key, Set<String>>> modules = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Request request : requests) {
            if (request == null || request.className() == null || !seen.add(request.className())) continue;
            String className = request.className();
            URI classFile = request.classFile();
            if (classFile == null) {
                unresolved(Reason.LAYOUT, className);
                continue;
            }
            if (archive(classFile)) {
                unresolved(Reason.ARCHIVE, className);
                continue;
            }
            Optional<LocalSourceModule> module = LocalSourceModule.of(className, classFile);
            if (module.isEmpty()) {
                unresolved(Reason.LAYOUT, className);
                continue;
            }
            if (!LocalSourceModule.sourceFileName(request.sourceFile())) {
                unresolved(Reason.NO_SOURCE_FILE, className);
                continue;
            }
            if (!modules.containsKey(module.get()) && modules.size() >= limits.modules()) {
                unresolved(Reason.BUDGET, className);
                continue;
            }
            classFiles.put(className, Path.of(classFile));
            int dot = className.lastIndexOf('.');
            Key key = new Key(dot < 0 ? "" : className.substring(0, dot), request.sourceFile());
            modules.computeIfAbsent(module.get(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .add(className);
        }
        SourceTreeReader tree = new SourceTreeReader(
                new SourceTreeReader.Limits(limits.entries(), limits.depth(), limits.fileBytes(), limits.totalBytes()),
                "Source-location",
                "unproven classes keep no source path.");
        modules.forEach((module, keys) -> resolveModule(tree, module, keys));
        return new Result(resolved, notes());
    }

    private void resolveModule(SourceTreeReader tree, LocalSourceModule module, Map<Key, Set<String>> keys) {
        verifyClassOutput(tree, module, keys);
        if (keys.isEmpty()) return;
        Set<String> fileNames = new LinkedHashSet<>();
        keys.keySet().forEach(key -> fileNames.add(key.fileName()));
        List<Path[]> candidates = new ArrayList<>();
        int links = tree.symbolicLinks();
        Path sourceSetRoot = module.sourceSetRoot();
        try {
            List<Path> roots = new ArrayList<>();
            roots.add(module.sourceSetRoot());
            roots.addAll(module.generatedRoots());
            for (Path root : roots) {
                if (!tree.safeDirectory(module.root(), root)) continue;
                tree.walk(
                        root,
                        path -> {
                            if (fileNames.contains(path.getFileName().toString())) {
                                candidates.add(new Path[] {root, path});
                            }
                        },
                        directory -> !SKIPPED_DIRECTORIES.contains(
                                        directory.getFileName().toString())
                                // Resources and web assets are never compiled, so a link there is irrelevant.
                                && !(sourceSetRoot.equals(directory.getParent())
                                        && NON_SOURCE_DIRECTORIES.contains(
                                                directory.getFileName().toString())));
            }
        } catch (SourceTreeReader.LimitException ex) {
            keys.values().forEach(classes -> unresolved(Reason.BUDGET, classes));
            return;
        } catch (IOException | DirectoryIteratorException | SecurityException ex) {
            if (tree.symbolicLinks() > links) {
                keys.values().forEach(classes -> unresolved(Reason.SYMBOLIC_LINK, classes));
            } else {
                keys.values().forEach(classes -> failed(ex.getClass().getSimpleName(), classes));
            }
            return;
        }

        Map<Key, List<Resolved>> matches = new LinkedHashMap<>();
        Set<String> unreadableNames = new LinkedHashSet<>();
        boolean exhausted = false;
        for (Path[] candidate : candidates) {
            Path root = candidate[0];
            Path path = candidate[1];
            String fileName = path.getFileName().toString();
            String text;
            SourceDeclarations declarations;
            try {
                text = tree.read(path);
                declarations = SourceDeclarations.read(text, fileName.endsWith(".kt"));
            } catch (SourceTreeReader.LimitException ex) {
                if (!"file-byte".equals(ex.kind())) {
                    exhausted = true;
                    break;
                }
                unreadableNames.add(fileName);
                continue;
            } catch (IOException | SecurityException ex) {
                unreadableNames.add(fileName);
                continue;
            }
            if (module.gradle()
                    && !root.equals(module.sourceSetRoot())
                    && LocalSourceModule.otherSourceSet(
                            root.relativize(path), module.sourceSet(), declarations.packageName())) {
                continue;
            }
            Key key = new Key(declarations.packageName(), fileName);
            if (keys.containsKey(key)) {
                matches.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new Resolved(path.toAbsolutePath().normalize(), lineCount(text)));
            }
        }
        for (Map.Entry<Key, Set<String>> entry : keys.entrySet()) {
            Key key = entry.getKey();
            Set<String> classes = entry.getValue();
            List<Resolved> found = matches.getOrDefault(key, List.of());
            if (exhausted) {
                unresolved(Reason.BUDGET, classes);
            } else if (found.size() > 1) {
                unresolved(Reason.AMBIGUOUS, classes);
            } else if (unreadableNames.contains(key.fileName())) {
                // An unparsed candidate with the same name could belong to this package.
                unresolved(Reason.UNREADABLE, classes);
            } else if (found.isEmpty()) {
                unresolved(Reason.NOT_FOUND, classes);
            } else {
                classes.forEach(className -> resolved.put(className, found.get(0)));
            }
        }
    }

    /**
     * Keeps only classes whose class file is a regular file reached from the module root without a symbolic link: the
     * module is derived from the class file's path, so a linked output directory could belong to another module.
     */
    private void verifyClassOutput(SourceTreeReader tree, LocalSourceModule module, Map<Key, Set<String>> keys) {
        Map<Path, Boolean> directories = new HashMap<>();
        for (Iterator<Map.Entry<Key, Set<String>>> entries = keys.entrySet().iterator(); entries.hasNext(); ) {
            Set<String> classes = entries.next().getValue();
            for (Iterator<String> names = classes.iterator(); names.hasNext(); ) {
                String className = names.next();
                if (!trustedClassFile(tree, module, classFiles.get(className), directories)) {
                    unresolved(Reason.UNTRUSTED_OUTPUT, className);
                    names.remove();
                }
            }
            if (classes.isEmpty()) entries.remove();
        }
    }

    private static boolean trustedClassFile(
            SourceTreeReader tree, LocalSourceModule module, Path classFile, Map<Path, Boolean> directories) {
        if (classFile == null || classFile.getParent() == null) return false;
        Path directory = classFile.getParent();
        Boolean safe = directories.get(directory);
        if (safe == null) {
            try {
                safe = tree.safeDirectory(module.root(), directory);
            } catch (IOException | SecurityException ex) {
                safe = false;
            }
            directories.put(directory, safe);
        }
        if (!safe) return false;
        // A missing or unreadable class file disqualifies only that class, never its siblings.
        try {
            return Files.readAttributes(classFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile();
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    static int lineCount(String text) {
        if (text.isEmpty()) return 0;
        int lines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') lines++;
        }
        return text.charAt(text.length() - 1) == '\n' ? lines : lines + 1;
    }

    private static boolean archive(URI classFile) {
        String scheme = classFile.getScheme();
        return scheme != null && (scheme.equals("jar") || scheme.equals("jrt") || scheme.equals("nested"));
    }

    private void unresolved(Reason reason, String className) {
        unresolved.computeIfAbsent(reason, ignored -> new LinkedHashSet<>()).add(className);
    }

    private void unresolved(Reason reason, Collection<String> classNames) {
        classNames.forEach(className -> unresolved(reason, className));
    }

    private void failed(String cause, Collection<String> classNames) {
        failures.computeIfAbsent(cause, ignored -> new LinkedHashSet<>()).addAll(classNames);
    }

    private List<String> notes() {
        List<String> notes = new ArrayList<>();
        unresolved.forEach((reason, classes) -> notes.add(String.format(reason.format, classes.size())));
        failures.forEach((cause, classes) -> notes.add(String.format(Reason.FAILED.format, cause, classes.size())));
        return notes;
    }
}
