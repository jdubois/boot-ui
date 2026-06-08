package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Inspects the classpath JARs to report which third-party dependencies ship GraalVM reachability
 * metadata under {@code META-INF/native-image/}. Only an actual reachability-metadata JSON file
 * (e.g. {@code reachability-metadata.json} or a {@code *-config.json}) counts as bundled metadata; a
 * bare {@code native-image.properties} only carries build arguments, not reachability metadata.
 * Dependencies without bundled metadata may still be covered by the GraalVM reachability metadata
 * repository, or may need manual hints.
 *
 * <p>The scan only opens JAR files (never the exploded application classes) and is bounded so a very
 * large classpath cannot make the panel expensive; if the bound is hit the survey reports a
 * truncation warning rather than silently dropping entries.</p>
 */
final class GraalVmDependencyScanner {

    private static final String METADATA_PREFIX = "META-INF/native-image/";
    private static final int MAX_DEPENDENCIES = 500;
    private static final String SHIPS_NOTE =
            "Ships GraalVM reachability metadata (reachability-metadata.json / *-config.json).";
    private static final String BUILD_ARGS_NOTE =
            "Bundles native-image build arguments (native-image.properties) but no reachability metadata JSON; "
                    + "runtime features may still need hints.";
    private static final String MISSING_NOTE =
            "No bundled metadata; may be covered by the GraalVM reachability metadata repository or need manual hints.";

    private final Supplier<String> classPathSupplier;

    GraalVmDependencyScanner() {
        this(() -> System.getProperty("java.class.path", ""));
    }

    GraalVmDependencyScanner(Supplier<String> classPathSupplier) {
        this.classPathSupplier = classPathSupplier;
    }

    DependencySurvey scan() {
        String classPath = classPathSupplier.get();
        if (classPath == null || classPath.isBlank()) {
            return new DependencySurvey(List.of(), false);
        }
        List<GraalVmDependencyDto> dependencies = new ArrayList<>();
        boolean truncated = false;
        for (String entry : classPath.split(File.pathSeparator)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || !trimmed.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            File file = new File(trimmed);
            if (!file.isFile()) {
                continue;
            }
            if (dependencies.size() >= MAX_DEPENDENCIES) {
                truncated = true;
                break;
            }
            dependencies.add(inspect(file));
        }
        dependencies.sort(Comparator.comparing(GraalVmDependencyDto::name));
        return new DependencySurvey(List.copyOf(dependencies), truncated);
    }

    static int maxDependencies() {
        return MAX_DEPENDENCIES;
    }

    private GraalVmDependencyDto inspect(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            boolean hasMetadataJson = false;
            boolean hasBuildArgs = false;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(METADATA_PREFIX)) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".json")) {
                    hasMetadataJson = true;
                    break;
                }
                if (lower.endsWith("native-image.properties")) {
                    hasBuildArgs = true;
                }
            }
            if (hasMetadataJson) {
                return new GraalVmDependencyDto(jar.getName(), true, SHIPS_NOTE);
            }
            if (hasBuildArgs) {
                return new GraalVmDependencyDto(jar.getName(), false, BUILD_ARGS_NOTE);
            }
            return new GraalVmDependencyDto(jar.getName(), false, MISSING_NOTE);
        } catch (Exception ex) {
            return new GraalVmDependencyDto(jar.getName(), false, "Could not read JAR: " + ex.getMessage());
        }
    }

    /** Outcome of a dependency survey: the inspected dependencies plus whether the bound was hit. */
    record DependencySurvey(List<GraalVmDependencyDto> dependencies, boolean truncated) {}
}
