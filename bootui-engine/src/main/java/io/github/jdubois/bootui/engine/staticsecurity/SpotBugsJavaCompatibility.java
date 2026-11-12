package io.github.jdubois.bootui.engine.staticsecurity;

import java.util.Optional;

/**
 * Decides whether a declared {@code spotbugs-maven-plugin} version can analyze a project's Java release.
 *
 * <p>SpotBugs reads class files through ASM, so a SpotBugs build that predates ASM support for a Java release fails on
 * that release's bytecode with {@code Unsupported class file major version N} and silently produces no findings. The
 * thresholds below are derived from primary sources (ASM release notes and the published {@code spotbugs} core POMs,
 * verified 2026-11-12):
 *
 * <ul>
 *   <li>spotbugs core 4.8.6 bundles ASM 9.7 (reads up to Java 23)</li>
 *   <li>spotbugs core 4.9.0–4.9.3 bundle ASM 9.7.1 (up to Java 24)</li>
 *   <li>spotbugs core 4.9.4+ bundle ASM 9.8 (Java 25)</li>
 * </ul>
 *
 * <p>The {@code spotbugs-maven-plugin} version {@code a.b.c.d} bundles spotbugs core {@code a.b.c}. Only Java releases
 * whose minimum is known are judged; newer releases return empty (unknown, never a false warning).
 */
final class SpotBugsJavaCompatibility {

    /** Minimum spotbugs core version required to analyze a given Java feature release. */
    private static final int[][] MINIMUMS = {
        // {javaRelease, major.minor.patch of the minimum spotbugs core}
        {24, 4, 9, 0},
        {25, 4, 9, 4},
    };

    /** The highest Java release with a known minimum; above this, compatibility is unknown. */
    private static final int HIGHEST_KNOWN_RELEASE = 25;

    private SpotBugsJavaCompatibility() {}

    /** A declared plugin version that cannot read {@code javaRelease}, with the core it bundles and the minimum needed. */
    record Result(String declaredCore, String minimumCore) {}

    /**
     * @param declaredPluginVersion the declared {@code spotbugs-maven-plugin} version, e.g. {@code 4.9.3.0}
     * @param javaRelease the project's Java feature release, e.g. {@code 25}; {@code 0} when unknown
     * @return a result when the declared version is known to be too old, otherwise empty
     */
    static Optional<Result> evaluate(String declaredPluginVersion, int javaRelease) {
        if (javaRelease <= 23 || javaRelease > HIGHEST_KNOWN_RELEASE) {
            return Optional.empty();
        }
        int[] core = parseCore(declaredPluginVersion);
        if (core == null) {
            return Optional.empty();
        }
        int[] minimum = minimumFor(javaRelease);
        if (minimum == null || compare(core, minimum) >= 0) {
            return Optional.empty();
        }
        return Optional.of(new Result(render(core), render(minimum)));
    }

    private static int[] minimumFor(int javaRelease) {
        for (int[] row : MINIMUMS) {
            if (row[0] == javaRelease) {
                return new int[] {row[1], row[2], row[3]};
            }
        }
        return null;
    }

    /** First three numeric segments of the plugin version (its bundled core); null if not a plain numeric version. */
    private static int[] parseCore(String version) {
        if (version == null || version.isBlank() || version.contains("$")) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 3) {
            return null;
        }
        int[] core = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!parts[i].matches("\\d+")) {
                return null;
            }
            core[i] = Integer.parseInt(parts[i]);
        }
        return core;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static String render(int[] core) {
        return core[0] + "." + core[1] + "." + core[2];
    }
}
