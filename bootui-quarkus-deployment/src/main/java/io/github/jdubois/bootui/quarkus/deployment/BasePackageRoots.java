package io.github.jdubois.bootui.quarkus.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Reduces the package names collected from the application's build-time Jandex index to a minimal,
 * bounded antichain of base-package roots for the BootUI ArchUnit advisors.
 *
 * <p>The reduction is deliberately defensive, because the resulting roots are handed straight to
 * ArchUnit's classpath importer, which resolves <em>by package name off the classpath</em> (not from the
 * index):</p>
 *
 * <ul>
 *   <li><strong>Blank / default-package names are dropped.</strong> A class in the default package yields
 *       an empty package name; passing {@code ""} to ArchUnit would import the entire classpath (every
 *       dependency jar), defeating the application-only bounding contract.</li>
 *   <li><strong>Single-segment roots are dropped</strong> (fail-soft). A root such as {@code org},
 *       {@code com} or {@code io} would make ArchUnit scan that whole top-level namespace across every
 *       dependency. A real application base package is essentially always at least two segments
 *       ({@code com.acme}, {@code io.github.x}); the rare single-segment app degrades to "nothing to
 *       analyse", which is the advisor's stable fail-soft state.</li>
 *   <li><strong>Descendants are folded into their ancestor</strong> (the antichain): if {@code a} is kept,
 *       any {@code a.b} below it is redundant.</li>
 *   <li><strong>The result is capped</strong> at {@link #MAX_ROOTS} in deterministic (sorted) order, so a
 *       pathological application with thousands of unrelated roots cannot blow up the scan.</li>
 * </ul>
 */
final class BasePackageRoots {

    /** Defensive upper bound on the number of base-package roots handed to the importer. */
    static final int MAX_ROOTS = 50;

    private BasePackageRoots() {}

    /**
     * Reduces raw package names to a minimal, bounded list of roots.
     *
     * @param packageNames the package names collected from the application index (may contain nulls,
     *     blanks, single-segment names, ancestors and descendants, in any order)
     * @return the minimal antichain of multi-segment roots, sorted and capped; never {@code null}
     */
    static List<String> reduce(Collection<String> packageNames) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String name : packageNames) {
            if (name == null) {
                continue;
            }
            String trimmed = name.trim();
            // Drop the default package ("") and single-segment roots: either would make ArchUnit scan far
            // beyond the application's own code.
            if (trimmed.indexOf('.') < 0) {
                continue;
            }
            sorted.add(trimmed);
        }

        List<String> roots = new ArrayList<>();
        for (String pkg : sorted) {
            // Ascending order guarantees ancestors are visited before their descendants, so a package is
            // redundant exactly when one of the already-kept roots is a proper prefix-ancestor of it.
            boolean covered = false;
            for (String root : roots) {
                if (pkg.startsWith(root + ".")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                roots.add(pkg);
                if (roots.size() >= MAX_ROOTS) {
                    break;
                }
            }
        }
        return List.copyOf(roots);
    }
}
