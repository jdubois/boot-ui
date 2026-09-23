package io.github.jdubois.bootui.engine.vulnerabilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Policy for recognizing an archive as the application's own code rather than a third-party dependency.
 *
 * <p>A multi-module build packages each sibling module as its own JAR next to the real dependencies (for example
 * {@code BOOT-INF/lib/orders.jar}). Such a JAR carries no Maven descriptor and is never in the application's SBOM,
 * so the archive census would otherwise report it as an unidentified, unscanned gap that no build change short of
 * publishing coordinates for every module could ever close.</p>
 *
 * <p>The rule is deliberately strict so that it can never hide a genuinely unidentified third-party archive: an
 * archive is first-party only when it contains at least one class and <em>every</em> class it contains lives in
 * one of the application's base packages (the same packages the Architecture advisor analyzes). A shaded or
 * third-party JAR fails at its first foreign class, and a resource-only JAR, a class in the default package, a
 * class under {@code META-INF/} (other than a multi-release variant), or an archive carrying any Maven descriptor
 * ({@code META-INF/maven/}, which shaded libraries usually keep), or bundling another archive ({@code .jar},
 * {@code .war}, {@code .zip}), is never first-party. A single-segment base
 * package such as {@code com} is too broad to separate an application from its dependencies and is ignored.</p>
 *
 * <p>Package containment remains a heuristic: a library the application relocated into its own namespace without
 * keeping its descriptor is indistinguishable from application code. Callers with stronger packaging evidence,
 * such as a Spring Boot layers index, should apply it as well.</p>
 */
public final class FirstPartyArchives {

    /** Upper bound on the entries inspected per archive; a larger archive is conservatively not first-party. */
    public static final int MAX_ENTRIES = 20_000;

    private static final String CLASS_SUFFIX = ".class";

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private static final String MODULE_INFO = "module-info.class";

    private static final String MAVEN_DESCRIPTORS = "META-INF/maven/";

    private FirstPartyArchives() {}

    /**
     * The usable base packages: trimmed, with blank and single-segment (for example {@code com}) entries removed.
     * An empty result means first-party recognition is impossible and every archive keeps its census
     * classification.
     */
    public static List<String> basePackages(Collection<String> basePackages) {
        if (basePackages == null) {
            return List.of();
        }
        List<String> packages = new ArrayList<>();
        for (String basePackage : basePackages) {
            String value = basePackage == null ? "" : basePackage.trim();
            while (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.indexOf('.') > 0 && !packages.contains(value)) {
                packages.add(value);
            }
        }
        return List.copyOf(packages);
    }

    /**
     * The packages an archive that a Spring Boot layers index places in its {@code application} layer may use:
     * the base packages plus each one's parent, when that parent still has at least two segments.
     *
     * <p>Spring Boot's default layering puts only the build's own project modules in {@code application}, and
     * the launcher module of a multi-module build often sits in a subpackage ({@code com.acme.gateway}) beside
     * its siblings ({@code com.acme.orders}). The layer placement alone is not trusted, since a custom layering
     * can assign any library there, so the classes must still share the application's namespace; the parent is
     * the smallest widening that covers sibling modules.</p>
     *
     * @param basePackages the application's base packages, as returned by {@link #basePackages(Collection)}
     */
    public static List<String> applicationLayerPackages(List<String> basePackages) {
        if (basePackages == null) {
            return List.of();
        }
        List<String> packages = new ArrayList<>(basePackages);
        for (String basePackage : basePackages) {
            int dot = basePackage.lastIndexOf('.');
            String parent = dot > 0 ? basePackage.substring(0, dot) : "";
            if (parent.indexOf('.') > 0 && !packages.contains(parent)) {
                packages.add(parent);
            }
        }
        return List.copyOf(packages);
    }

    /**
     * Whether the archive whose entry names are {@code entryNames} is the application's own code.
     *
     * <p>{@code entryNames} is iterated lazily and iteration stops at the first class outside the base packages,
     * so a caller can stream a large third-party archive's entries at the cost of reading only its first
     * class.</p>
     *
     * @param entryNames the archive's entry names, in archive order ({@code /}-separated)
     * @param basePackages the application's base packages, as returned by {@link #basePackages(Collection)}
     */
    public static boolean isFirstParty(Iterable<String> entryNames, List<String> basePackages) {
        if (entryNames == null || basePackages == null || basePackages.isEmpty()) {
            return false;
        }
        boolean applicationClass = false;
        int inspected = 0;
        for (String entryName : entryNames) {
            if (++inspected > MAX_ENTRIES) {
                return false;
            }
            if (entryName == null) {
                continue;
            }
            if (entryName.startsWith(MAVEN_DESCRIPTORS) || bundlesArchive(entryName)) {
                return false;
            }
            if (!entryName.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            String name = withoutVersionPrefix(entryName);
            if (name == null) {
                return false;
            }
            if (name.equals(MODULE_INFO)) {
                continue;
            }
            int slash = name.lastIndexOf('/');
            if (slash <= 0 || name.startsWith("META-INF/")) {
                return false;
            }
            if (!inBasePackages(name.substring(0, slash).replace('/', '.'), basePackages)) {
                return false;
            }
            applicationClass = true;
        }
        return applicationClass;
    }

    /** Whether the entry is itself an archive, whose contents would otherwise be hidden unscanned. */
    private static boolean bundlesArchive(String entryName) {
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".zip");
    }

    /** The class entry name with a multi-release {@code META-INF/versions/<n>/} prefix removed. */
    private static String withoutVersionPrefix(String entryName) {
        if (!entryName.startsWith(VERSIONS_PREFIX)) {
            return entryName;
        }
        int slash = entryName.indexOf('/', VERSIONS_PREFIX.length());
        if (slash <= VERSIONS_PREFIX.length()) {
            return null;
        }
        for (int i = VERSIONS_PREFIX.length(); i < slash; i++) {
            if (!Character.isDigit(entryName.charAt(i))) {
                return null;
            }
        }
        return entryName.substring(slash + 1);
    }

    private static boolean inBasePackages(String packageName, List<String> basePackages) {
        for (String basePackage : basePackages) {
            if (packageName.equals(basePackage) || packageName.startsWith(basePackage + ".")) {
                return true;
            }
        }
        return false;
    }
}
