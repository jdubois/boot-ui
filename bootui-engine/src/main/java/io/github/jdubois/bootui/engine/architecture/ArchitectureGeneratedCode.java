package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import io.github.jdubois.bootui.engine.source.LocalSourceModule;
import io.github.jdubois.bootui.engine.source.SourceDeclarations;
import io.github.jdubois.bootui.engine.source.SourceTreeReader;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    private static boolean inspectForConflicts(LocalSourceModule module, Path directory) {
        if (DEPENDENCY_DIRECTORIES.contains(directory.getFileName().toString())) return false;
        Path root = module.root();
        return !Set.of(
                        root.resolve("target/classes"),
                        root.resolve("target/test-classes"),
                        root.resolve("target/generated-sources"),
                        root.resolve("target/generated-test-sources"),
                        root.resolve("build/classes"),
                        root.resolve("build/generated"),
                        root.resolve("build/generate-resources"),
                        root.resolve("src/" + (module.sourceSet().equals("main") ? "test" : "main")))
                .contains(directory);
    }

    private final Limits limits;
    private final SourceTreeReader tree;
    private final Set<String> limitations = new LinkedHashSet<>();

    private ArchitectureGeneratedCode(Limits limits) {
        this.limits = limits;
        this.tree = new SourceTreeReader(
                new SourceTreeReader.Limits(limits.entries(), limits.depth(), limits.fileBytes(), limits.totalBytes()),
                "Generated-source",
                "uncertain classes remain included.");
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
        Map<LocalSourceModule, List<Candidate>> modules = new LinkedHashMap<>();
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
            Optional<LocalSourceModule> module =
                    LocalSourceModule.of(type.getName(), source.get().getUri());
            if (module.isEmpty()) continue;
            if (!modules.containsKey(module.get()) && modules.size() >= limits.modules()) {
                limitations.add("Generated-source module limit reached; uncertain classes remain included.");
                continue;
            }
            modules.computeIfAbsent(module.get(), ignored -> new ArrayList<>())
                    .add(new Candidate(type, source.get().getFileName().orElseThrow(), owner.getSimpleName()));
        }
        for (Map.Entry<LocalSourceModule, List<Candidate>> entry : modules.entrySet()) {
            try {
                generated.addAll(resolveModule(entry.getKey(), entry.getValue()));
            } catch (SourceTreeReader.LimitException ex) {
                limitations.add(ex.getMessage());
            } catch (IOException | DirectoryIteratorException | SecurityException ex) {
                limitations.add("Generated-source lookup failed ("
                        + ex.getClass().getSimpleName() + "); uncertain classes remain included.");
            }
        }
        return new Result(generated, List.copyOf(limitations), templates);
    }

    private Set<String> resolveModule(LocalSourceModule module, List<Candidate> candidates) throws IOException {
        Set<String> fileNames = new HashSet<>();
        candidates.forEach(candidate -> fileNames.add(candidate.fileName()));
        Map<SourceKey, Integer> generatedSources = new HashMap<>();
        List<Path> roots = new ArrayList<>();
        for (Path root : module.generatedRoots()) {
            if (tree.safeDirectory(module.root(), root)) roots.add(root);
        }
        if (roots.isEmpty()) return Set.of();
        Set<Path> checkedClassDirectories = new HashSet<>();
        for (Candidate candidate : candidates) {
            Path classFile = Path.of(candidate.type().getSource().orElseThrow().getUri());
            if (checkedClassDirectories.add(classFile.getParent())
                    && !tree.safeDirectory(module.root(), classFile.getParent())) return Set.of();
            if (!Files.readAttributes(classFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile()) {
                throw new IOException("Unresolved compiled source");
            }
        }
        for (Path root : roots) {
            tree.walk(
                    root,
                    path -> {
                        if (!fileNames.contains(path.getFileName().toString())) return;
                        SourceDeclarations declarations = declarations(path);
                        if (module.gradle()
                                && LocalSourceModule.otherSourceSet(
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
        tree.walk(
                module.root(),
                path -> {
                    if (sourceFile(path.getFileName().toString())) {
                        SourceDeclarations declarations = declarations(path);
                        declarations
                                .typeNames()
                                .forEach(name -> handwrittenTypes.add(declarations.packageName() + "." + name));
                    } else if (!module.gradle()
                            && path.startsWith(module.root().resolve("target/maven-status/maven-compiler-plugin"))
                            && path.getFileName().toString().equals("inputFiles.lst")) {
                        for (String input : tree.read(path)
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
                directory -> inspectForConflicts(module, directory));

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

    private SourceDeclarations declarations(Path path) throws IOException {
        return SourceDeclarations.read(
                tree.read(path), path.getFileName().toString().endsWith(".kt"));
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
        return LocalSourceModule.sourceFileName(name);
    }
}
