package io.github.jdubois.bootui.engine.source;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The local Maven or Gradle module and source set a class was compiled into, derived only from where its
 * class file sits: {@code target/classes}, {@code target/test-classes}, or
 * {@code build/classes/{java,kotlin}/{main,test}}. Anything else, including archives, extracted
 * {@code BOOT-INF} images, and Quarkus {@code lib} layouts, has no local module.
 *
 * <p>Deriving the module from the output directory rather than from the working directory is what lets a
 * multi-module project launched from its reactor root map each class to its own module's sources.</p>
 *
 * @param root the module directory, the parent of {@code target} or {@code build}
 * @param gradle whether the output directory follows the Gradle layout
 * @param sourceSet {@code main} or {@code test}
 */
public record LocalSourceModule(Path root, boolean gradle, String sourceSet) {

    /** The module-local directories generated sources are written to for this source set. */
    public List<Path> generatedRoots() {
        return gradle
                ? List.of(root.resolve("build/generated"), root.resolve("build/generate-resources/" + sourceSet))
                : List.of(root.resolve(
                        "target/" + (sourceSet.equals("test") ? "generated-test-sources" : "generated-sources")));
    }

    /** The handwritten source-set directory, {@code src/main} or {@code src/test}. */
    public Path sourceSetRoot() {
        return root.resolve("src").resolve(sourceSet);
    }

    /**
     * The module a class compiled into a local output directory belongs to, or empty when its class file is not
     * a plain, normalized {@code file:} location whose path ends with the class's own package directories.
     */
    public static Optional<LocalSourceModule> of(String className, URI classFile) {
        if (className == null
                || classFile == null
                || !"file".equals(classFile.getScheme())
                || classFile.getAuthority() != null
                || classFile.getQuery() != null
                || classFile.getFragment() != null) {
            return Optional.empty();
        }
        String[] names = className.split("\\.", -1);
        for (String name : names) {
            if (!safeSegment(name)) return Optional.empty();
        }
        Path file;
        try {
            file = Path.of(classFile);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException ex) {
            return Optional.empty();
        }
        if (!file.equals(file.normalize())) return Optional.empty();
        Path output = file;
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
                return Optional.of(new LocalSourceModule(output.getParent().getParent(), false, sourceSet));
            }
            for (String language : List.of("java", "kotlin")) {
                if (output.endsWith(Path.of("build", "classes", language, sourceSet))) {
                    Path root = output.getParent().getParent().getParent().getParent();
                    if (root != null) return Optional.of(new LocalSourceModule(root, true, sourceSet));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a path under a Gradle generated root belongs to the other source set. Only layout prefixes
     * identify source sets, never a package directory that happens to be named {@code main} or {@code test}.
     *
     * @param relative the path relative to the generated root
     * @param packageName the package the file declares, so its package directories are never mistaken for a
     *     source-set prefix
     */
    public static boolean otherSourceSet(Path relative, String sourceSet, String packageName) {
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

    /** Whether {@code name} is a single, non-traversing path segment free of separators and control characters. */
    public static boolean safeSegment(String name) {
        return !name.isBlank()
                && !name.equals(".")
                && !name.equals("..")
                && name.chars().noneMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c));
    }

    /** Whether {@code name} is a safe {@code .java} or {@code .kt} source file name. */
    public static boolean sourceFileName(String name) {
        return name != null && safeSegment(name) && (name.endsWith(".java") || name.endsWith(".kt"));
    }
}
