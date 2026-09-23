package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Scan-local generated-source provenance. Standard Generated annotations have SOURCE retention;
 * compiled output directories alone cannot distinguish generated from handwritten application code.
 */
final class ArchitectureGeneratedCode {
    static final Limits DEFAULT_LIMITS = new Limits(64, 50_000, 32, 256 * 1024, 16 * 1024 * 1024);
    private static final Set<String> ANNOTATIONS = Set.of(
            "jakarta.annotation.Generated", "javax.annotation.Generated", "javax.annotation.processing.Generated");
    private static final Set<String> DEPENDENCY_DIRECTORIES = Set.of(".git", ".gradle", ".m2", "node_modules");

    record Limits(int modules, int entries, int depth, int fileBytes, int totalBytes) {
        Limits {
            if (modules < 1 || entries < 1 || depth < 1 || fileBytes < 1 || totalBytes < 1) {
                throw new IllegalArgumentException("Generated-source lookup limits must be positive.");
            }
        }
    }

    /**
     * @param templateClasses the subset of {@code generatedClasses} recognised by bytecode template shape
     *     because no local source provenance was available
     */
    record Result(Set<String> generatedClasses, List<String> limitations, Set<String> templateClasses) {
        Result {
            generatedClasses = Set.copyOf(generatedClasses);
            limitations = List.copyOf(limitations);
            templateClasses = Set.copyOf(templateClasses);
        }

        Result(Set<String> generatedClasses, List<String> limitations) {
            this(generatedClasses, limitations, Set.of());
        }

        JavaClasses handwrittenClasses(JavaClasses classes) {
            return classes.that(new DescribedPredicate<>("not positively identified as generated") {
                @Override
                public boolean test(JavaClass type) {
                    return !generatedClasses.contains(type.getName());
                }
            });
        }
    }

    private record Candidate(JavaClass type, String fileName, String topLevelName) {}

    private record SourceKey(String packageName, String fileName, String typeName) {}

    private record Module(Path root, boolean gradle, String sourceSet) {
        List<Path> generatedRoots() {
            return gradle
                    ? List.of(root.resolve("build/generated"), root.resolve("build/generate-resources/" + sourceSet))
                    : List.of(root.resolve(
                            "target/" + (sourceSet.equals("test") ? "generated-test-sources" : "generated-sources")));
        }

        boolean inspectForConflicts(Path directory) {
            if (DEPENDENCY_DIRECTORIES.contains(directory.getFileName().toString())) return false;
            return !Set.of(
                            root.resolve("target/classes"),
                            root.resolve("target/test-classes"),
                            root.resolve("target/generated-sources"),
                            root.resolve("target/generated-test-sources"),
                            root.resolve("build/classes"),
                            root.resolve("build/generated"),
                            root.resolve("build/generate-resources"),
                            root.resolve("src/" + (sourceSet.equals("main") ? "test" : "main")))
                    .contains(directory);
        }
    }

    private final Limits limits;
    private final Set<String> limitations = new LinkedHashSet<>();
    private int entries;
    private int bytes;

    private ArchitectureGeneratedCode(Limits limits) {
        this.limits = limits;
    }

    static Result resolve(JavaClasses classes) {
        return resolve(classes, DEFAULT_LIMITS);
    }

    static Result resolve(JavaClasses classes, Limits limits) {
        return new ArchitectureGeneratedCode(limits).scan(classes);
    }

    private Result scan(JavaClasses classes) {
        Set<String> generated = new HashSet<>();
        Set<String> templates = new HashSet<>();
        Map<Module, List<Candidate>> modules = new LinkedHashMap<>();
        for (JavaClass type : classes.stream()
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList()) {
            JavaClass owner = enclosingType(type);
            if (hasGeneratedAnnotation(type) || hasGeneratedAnnotation(owner)) {
                generated.add(type.getName());
                continue;
            }
            if (!hasSourceClassDeclaration(owner)) continue;
            Optional<Source> source = type.getSource();
            if (source.isPresent()
                    && OpenApiGeneratorApiUtilFingerprint.packaged(source.get().getUri())) {
                // Archive classes can never have local source ownership. Unresolved local classes, including
                // unsupported output layouts and missing SourceFile metadata, stay eligible instead.
                if (OpenApiGeneratorApiUtilFingerprint.matches(type)) {
                    generated.add(type.getName());
                    templates.add(type.getName());
                }
                continue;
            }
            if (source.isEmpty()
                    || source.get()
                            .getFileName()
                            .filter(ArchitectureGeneratedCode::sourceFile)
                            .isEmpty()) {
                continue;
            }
            Optional<Module> module = module(type, source.get());
            if (module.isEmpty()) continue;
            if (!modules.containsKey(module.get()) && modules.size() >= limits.modules()) {
                limitations.add("Generated-source module limit reached; uncertain classes remain included.");
                continue;
            }
            modules.computeIfAbsent(module.get(), ignored -> new ArrayList<>())
                    .add(new Candidate(type, source.get().getFileName().orElseThrow(), owner.getSimpleName()));
        }
        for (Map.Entry<Module, List<Candidate>> entry : modules.entrySet()) {
            try {
                generated.addAll(resolveModule(entry.getKey(), entry.getValue()));
            } catch (LookupLimitException ex) {
                limitations.add(ex.getMessage());
            } catch (IOException | DirectoryIteratorException | SecurityException ex) {
                limitations.add("Generated-source lookup failed ("
                        + ex.getClass().getSimpleName() + "); uncertain classes remain included.");
            }
        }
        return new Result(generated, List.copyOf(limitations), templates);
    }

    private Set<String> resolveModule(Module module, List<Candidate> candidates) throws IOException {
        Set<String> fileNames = new HashSet<>();
        candidates.forEach(candidate -> fileNames.add(candidate.fileName()));
        Map<SourceKey, Integer> generatedSources = new HashMap<>();
        List<Path> roots = new ArrayList<>();
        for (Path root : module.generatedRoots()) {
            if (safeDirectory(module.root(), root)) roots.add(root);
        }
        if (roots.isEmpty()) return Set.of();
        Set<Path> checkedClassDirectories = new HashSet<>();
        for (Candidate candidate : candidates) {
            Path classFile = Path.of(candidate.type().getSource().orElseThrow().getUri());
            if (checkedClassDirectories.add(classFile.getParent())
                    && !safeDirectory(module.root(), classFile.getParent())) return Set.of();
            if (!Files.readAttributes(classFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile()) {
                throw new IOException("Unresolved compiled source");
            }
        }
        for (Path root : roots) {
            walk(
                    root,
                    0,
                    path -> {
                        if (!fileNames.contains(path.getFileName().toString())) return;
                        GeneratedSourceDeclarations declarations = declarations(path);
                        if (module.gradle()
                                && otherSourceSet(
                                        root.relativize(path), module.sourceSet(), declarations.packageName())) return;
                        for (String name : declarations.typeNames()) {
                            generatedSources.merge(
                                    new SourceKey(
                                            declarations.packageName(),
                                            path.getFileName().toString(),
                                            name),
                                    1,
                                    Integer::sum);
                        }
                    },
                    ignored -> true);
        }
        if (generatedSources.isEmpty()) return Set.of();

        Set<String> handwrittenTypes = new HashSet<>();
        // Standard class output does not imply standard source roots. Inspect module-local custom
        // roots too, and reject external compiler inputs without reading outside the module.
        walk(
                module.root(),
                0,
                path -> {
                    if (sourceFile(path.getFileName().toString())) {
                        GeneratedSourceDeclarations declarations = declarations(path);
                        declarations
                                .typeNames()
                                .forEach(name -> handwrittenTypes.add(declarations.packageName() + "." + name));
                    } else if (!module.gradle()
                            && path.startsWith(module.root().resolve("target/maven-status/maven-compiler-plugin"))
                            && path.getFileName().toString().equals("inputFiles.lst")) {
                        for (String input : sourceText(path)
                                .lines()
                                .filter(line -> !line.isBlank())
                                .toList()) {
                            Path source = Path.of(input);
                            if (!source.isAbsolute() || !source.normalize().startsWith(module.root())) {
                                throw new IOException("Compiler source ownership extends outside the module.");
                            }
                        }
                    }
                },
                module::inspectForConflicts);

        Set<String> result = new HashSet<>();
        for (Candidate candidate : candidates) {
            String packageName = candidate.type().getPackageName();
            SourceKey key = new SourceKey(packageName, candidate.fileName(), candidate.topLevelName());
            if (generatedSources.getOrDefault(key, 0) == 1
                    && !handwrittenTypes.contains(packageName + "." + candidate.topLevelName())) {
                result.add(candidate.type().getName());
            }
        }
        return result;
    }

    private static boolean otherSourceSet(Path relative, String sourceSet, String packageName) {
        int prefixEnd = relative.getNameCount() - 1;
        if (!packageName.isEmpty()) {
            String[] segments = packageName.split("\\.");
            int packageStart = prefixEnd - segments.length;
            boolean matches = packageStart >= 0;
            for (int i = 0; matches && i < segments.length; i++) {
                matches = relative.getName(packageStart + i).toString().equals(segments[i]);
            }
            if (matches) prefixEnd = packageStart;
        }
        for (int i = 0; i < prefixEnd; i++) {
            String name = relative.getName(i).toString();
            // Only layout prefixes identify source sets, never a package directory named main/test.
            if (name.equals("src")
                    && i + 2 < prefixEnd
                    && Set.of("java", "kotlin").contains(relative.getName(i + 2).toString())) {
                return !relative.getName(i + 1).toString().equals(sourceSet);
            }
            if ((name.equals("sources") || name.equals("source")) && i + 2 < prefixEnd) {
                int sourceSetIndex = i + 2;
                if (Set.of("java", "kotlin")
                                .contains(relative.getName(sourceSetIndex).toString())
                        && sourceSetIndex + 1 < prefixEnd) sourceSetIndex++;
                String observed = relative.getName(sourceSetIndex).toString();
                if (Set.of("main", "test").contains(observed)) return !observed.equals(sourceSet);
            }
        }
        return false;
    }

    private boolean safeDirectory(Path module, Path directory) throws IOException {
        Path current = module;
        if (!directory.startsWith(module)) return false;
        for (Path segment : module.relativize(directory)) {
            if (!directory(current)) return false;
            current = current.resolve(segment);
        }
        return directory(current);
    }

    private boolean directory(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return false;
        }
        if (attributes.isSymbolicLink()) throw new IOException("Symbolic source root");
        return attributes.isDirectory();
    }

    @FunctionalInterface
    private interface SourceVisitor {
        void visit(Path path) throws IOException;
    }

    private void walk(Path directory, int depth, SourceVisitor visitor, Predicate<Path> descend) throws IOException {
        if (depth > limits.depth()) throw limit("depth");
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++entries > limits.entries()) throw limit("entry");
                if (!descend.test(child)) continue;
                BasicFileAttributes attributes =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // A skipped subtree could contain a conflicting handwritten declaration.
                if (attributes.isSymbolicLink()) throw new IOException("Symbolic source entry");
                if (attributes.isDirectory()) walk(child, depth + 1, visitor, descend);
                else if (attributes.isRegularFile()) visitor.visit(child);
            }
        }
    }

    private GeneratedSourceDeclarations declarations(Path path) throws IOException {
        return GeneratedSourceDeclarations.read(
                sourceText(path), path.getFileName().toString().endsWith(".kt"));
    }

    private String sourceText(Path path) throws IOException {
        int remaining = Math.min(limits.fileBytes(), limits.totalBytes() - bytes);
        if (remaining <= 0) throw limit("total-byte");
        ByteBuffer buffer = ByteBuffer.allocate(remaining + 1);
        try (SeekableByteChannel channel =
                Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read at most one byte beyond the budget, even if the source grows during lookup.
            }
        }
        bytes += buffer.position();
        if (buffer.position() > remaining) {
            throw limit(remaining == limits.fileBytes() ? "file-byte" : "total-byte");
        }
        buffer.flip();
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(buffer)
                .toString();
    }

    private LookupLimitException limit(String kind) {
        return new LookupLimitException(
                "Generated-source " + kind + " limit reached; uncertain classes remain included.");
    }

    private static final class LookupLimitException extends IOException {
        LookupLimitException(String message) {
            super(message);
        }
    }

    private static JavaClass enclosingType(JavaClass type) {
        Set<String> visited = new HashSet<>();
        while (type.getEnclosingClass().isPresent() && visited.add(type.getName())) {
            type = type.getEnclosingClass().orElseThrow();
        }
        return type;
    }

    private static boolean hasGeneratedAnnotation(JavaClass type) {
        return type.getAnnotations().stream()
                .anyMatch(annotation ->
                        ANNOTATIONS.contains(annotation.getRawType().getName()));
    }

    private static boolean hasSourceClassDeclaration(JavaClass type) {
        for (var annotation : type.getAnnotations()) {
            if (annotation.getRawType().getName().equals("kotlin.Metadata")) {
                return annotation
                        .get("k")
                        .filter(value -> Integer.valueOf(1).equals(value))
                        .isPresent();
            }
        }
        return true;
    }

    private static boolean sourceFile(String name) {
        return safeSegment(name) && (name.endsWith(".java") || name.endsWith(".kt"));
    }

    private static boolean safeSegment(String name) {
        return !name.isBlank()
                && !name.equals(".")
                && !name.equals("..")
                && name.chars().noneMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c));
    }

    private static Optional<Module> module(JavaClass type, Source source) {
        if (!"file".equals(source.getUri().getScheme())
                || source.getUri().getAuthority() != null
                || source.getUri().getQuery() != null
                || source.getUri().getFragment() != null) {
            return Optional.empty();
        }
        String[] names = type.getName().split("\\.", -1);
        for (String name : names) {
            if (!safeSegment(name)) return Optional.empty();
        }
        Path classFile;
        try {
            classFile = Path.of(source.getUri());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!classFile.equals(classFile.normalize())) return Optional.empty();
        Path output = classFile;
        for (int i = names.length - 1; i >= 0; i--) {
            if (output == null
                    || output.getFileName() == null
                    || !output.getFileName().toString().equals(names[i] + (i == names.length - 1 ? ".class" : ""))) {
                return Optional.empty();
            }
            output = output.getParent();
        }
        if (output == null) return Optional.empty();
        for (String sourceSet : List.of("main", "test")) {
            Path maven = Path.of("target", sourceSet.equals("main") ? "classes" : "test-classes");
            if (output.endsWith(maven) && output.getParent().getParent() != null) {
                return Optional.of(new Module(output.getParent().getParent(), false, sourceSet));
            }
            for (String language : List.of("java", "kotlin")) {
                if (output.endsWith(Path.of("build", "classes", language, sourceSet))) {
                    Path root = output.getParent().getParent().getParent().getParent();
                    if (root != null) return Optional.of(new Module(root, true, sourceSet));
                }
            }
        }
        return Optional.empty();
    }
}
