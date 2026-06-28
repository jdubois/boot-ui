package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * Resolves the host application's local dependency inventory on Quarkus — the Quarkus analogue of the
 * Spring adapter's {@code DependencyCatalog} (which scans the classpath for {@code META-INF/maven/*}{@code
 * /pom.properties}).
 *
 * <p>The Spring classpath scan is unreliable under the Quarkus runtime classloader
 * ({@code QuarkusClassLoader} does not consistently enumerate {@code getResources(META-INF/maven/...)}
 * across dependency jars, and {@code java.class.path} is not the real classpath in fast-jar/native
 * layouts). So the inventory is captured at <em>build time</em> from the fully-resolved application
 * dependency model ({@code CurateOutcomeBuildItem.getApplicationModel().getRuntimeDependencies()}) by the
 * deployment processor, encoded as the runtime config default {@code bootui.internal.dependencies} (a
 * comma-separated list of {@code groupId:artifactId:version} coordinates), and read back here. This
 * mirrors the Architecture advisor's build-time base-package discovery
 * ({@code QuarkusBasePackageProvider} reading {@code bootui.internal.base-packages}) exactly.</p>
 *
 * <p>Like every build-time-populated provider it fails soft: a missing or blank key yields an empty list,
 * so the Vulnerabilities panel renders an empty inventory rather than failing. Entries are de-duplicated by
 * {@code group:artifact:version} (resolved dependencies may repeat across classifiers) and sorted by
 * package name then version, matching the Spring catalogue. Each coordinate becomes a
 * {@link DependencyDto} with no vulnerabilities until the user triggers an OSV scan.</p>
 */
@Singleton
public class QuarkusDependencyProvider implements DependencyProvider {

    /**
     * Runtime config key holding the comma-separated {@code groupId:artifactId:version} application
     * dependency coordinates, populated as a build-time default by the deployment processor. Mirrors
     * {@link QuarkusBasePackageProvider#BASE_PACKAGES_KEY}.
     */
    public static final String DEPENDENCIES_KEY = "bootui.internal.dependencies";

    private static final String SOURCE = "Quarkus application model";

    private final Config config;

    @Inject
    public QuarkusDependencyProvider(Config config) {
        this.config = config;
    }

    @Override
    public List<DependencyDto> dependencies() {
        return config.getOptionalValue(DEPENDENCIES_KEY, String.class)
                .map(QuarkusDependencyProvider::parse)
                .orElseGet(List::of);
    }

    private static List<DependencyDto> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            DependencyDto dependency = coordinate(entry.trim());
            if (dependency != null) {
                dependencies.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
            }
        }
        return dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
    }

    private static DependencyDto coordinate(String entry) {
        if (entry.isEmpty()) {
            return null;
        }
        // groupId:artifactId:version — group/artifact never contain ':'; the version takes the remainder.
        int firstColon = entry.indexOf(':');
        if (firstColon <= 0) {
            return null;
        }
        int secondColon = entry.indexOf(':', firstColon + 1);
        if (secondColon <= firstColon + 1 || secondColon >= entry.length() - 1) {
            return null;
        }
        String groupId = entry.substring(0, firstColon);
        String artifactId = entry.substring(firstColon + 1, secondColon);
        String version = entry.substring(secondColon + 1);
        if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
            return null;
        }
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(groupId, artifactId, version, packageName, SOURCE, 0, "NONE", List.of());
    }
}
