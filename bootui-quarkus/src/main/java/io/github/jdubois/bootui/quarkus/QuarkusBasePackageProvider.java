package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.BasePackageProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * Resolves the host application's own base packages on Quarkus — the Quarkus analogue of the Spring
 * adapter's {@code AutoConfigurationPackages}-backed provider.
 *
 * <p>Quarkus has no runtime equivalent of {@code AutoConfigurationPackages}, so the package roots are
 * computed at <em>build time</em> from the application's own Jandex index
 * ({@code ApplicationIndexBuildItem}, the application root archive only — never its dependency jars) by
 * the deployment processor, reduced to a minimal antichain of roots, and surfaced as the runtime config
 * default {@code bootui.internal.base-packages} (a comma-separated list). This provider simply reads that
 * key live on every scan and splits it.</p>
 *
 * <p>Like every {@link BasePackageProvider}, it fails soft: a missing or blank key yields an empty list,
 * so the ArchUnit advisors degrade to a stable "nothing to analyse" report rather than failing. Because
 * the key is a runtime <em>default</em>, an application that splits its code across sibling modules (which
 * the application root index does not span) can override it explicitly in {@code application.properties}.</p>
 */
@Singleton
public class QuarkusBasePackageProvider implements BasePackageProvider {

    /**
     * Runtime config key holding the comma-separated application base packages, populated as a build-time
     * default by the deployment processor. Mirrors {@code QuarkusApplicationInfo.QUARKUS_VERSION_KEY}.
     */
    public static final String BASE_PACKAGES_KEY = "bootui.internal.base-packages";

    private final Config config;

    @Inject
    public QuarkusBasePackageProvider(Config config) {
        this.config = config;
    }

    @Override
    public List<String> basePackages() {
        return config.getOptionalValue(BASE_PACKAGES_KEY, String.class)
                .map(QuarkusBasePackageProvider::split)
                .orElseGet(List::of);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> packages = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed);
            }
        }
        return List.copyOf(packages);
    }
}
