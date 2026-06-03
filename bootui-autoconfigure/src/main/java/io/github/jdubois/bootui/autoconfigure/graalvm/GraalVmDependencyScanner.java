package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Inspects the classpath JARs to report which third-party dependencies ship GraalVM reachability
 * metadata under {@code META-INF/native-image/}. Dependencies without bundled metadata may still be
 * covered by the GraalVM reachability metadata repository, or may need manual hints.
 *
 * <p>The scan only opens JAR files (never the exploded application classes) and is bounded so a very
 * large classpath cannot make the panel expensive.</p>
 */
final class GraalVmDependencyScanner {

    private static final String METADATA_PREFIX = "META-INF/native-image/";
    private static final int MAX_DEPENDENCIES = 500;
    private static final String SHIPS_NOTE = "Ships GraalVM reachability metadata.";
    private static final String MISSING_NOTE =
            "No bundled metadata; may be covered by the GraalVM reachability metadata repository or need manual hints.";

    private final Supplier<String> classPathSupplier;

    GraalVmDependencyScanner() {
        this(() -> System.getProperty("java.class.path", ""));
    }

    GraalVmDependencyScanner(Supplier<String> classPathSupplier) {
        this.classPathSupplier = classPathSupplier;
    }

    List<GraalVmDependencyDto> scan() {
        String classPath = classPathSupplier.get();
        if (classPath == null || classPath.isBlank()) {
            return List.of();
        }
        List<GraalVmDependencyDto> dependencies = new ArrayList<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            if (dependencies.size() >= MAX_DEPENDENCIES) {
                break;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || !trimmed.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            File file = new File(trimmed);
            if (!file.isFile()) {
                continue;
            }
            dependencies.add(inspect(file));
        }
        dependencies.sort(Comparator.comparing(GraalVmDependencyDto::name));
        return List.copyOf(dependencies);
    }

    private GraalVmDependencyDto inspect(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(METADATA_PREFIX)
                        && !entry.getName().equals(METADATA_PREFIX)) {
                    return new GraalVmDependencyDto(jar.getName(), true, SHIPS_NOTE);
                }
            }
            return new GraalVmDependencyDto(jar.getName(), false, MISSING_NOTE);
        } catch (Exception ex) {
            return new GraalVmDependencyDto(jar.getName(), false, "Could not read JAR: " + ex.getMessage());
        }
    }
}
