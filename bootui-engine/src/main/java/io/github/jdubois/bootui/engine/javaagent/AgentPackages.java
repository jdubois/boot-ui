package io.github.jdubois.bootui.engine.javaagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The application package prefixes a claim passes to the agent ({@code docs/PLAN-v2.md} §5.13): the packages of the
 * application's classes, reduced to their common prefixes, without BootUI's own modules.
 */
public final class AgentPackages {

    /** BootUI's own module packages, never instrumented as application code. Its sample applications are. */
    static final List<String> BOOTUI_PACKAGES = List.of(
            "io.github.jdubois.bootui.engine",
            "io.github.jdubois.bootui.core",
            "io.github.jdubois.bootui.spi",
            "io.github.jdubois.bootui.quarkus",
            "io.github.jdubois.bootui.autoconfigure",
            "io.github.jdubois.bootui.agent");

    private AgentPackages() {}

    /**
     * Reduces package names to the longest prefix each group shares, a group being the packages with the same first two
     * segments, so {@code com.example.app.web} and {@code com.example.app.data} become {@code com.example.app}. A prefix
     * never has fewer than two segments unless the package itself is that short, so two unrelated libraries under one
     * top-level domain never merge into it. BootUI's own module packages are left out first.
     */
    public static List<String> reduce(Collection<String> packages) {
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (String name : new TreeSet<>(packages == null ? List.of() : packages)) {
            if (name == null || name.isBlank() || isBootUi(name)) {
                continue;
            }
            String[] segments = name.trim().split("\\.");
            String key = segments.length < 2 ? segments[0] : segments[0] + "." + segments[1];
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segments);
        }
        List<String> prefixes = new ArrayList<>();
        for (List<String[]> group : groups.values()) {
            String[] first = group.get(0);
            int common = first.length;
            for (String[] other : group) {
                int shared = 0;
                while (shared < Math.min(common, other.length) && first[shared].equals(other[shared])) {
                    shared++;
                }
                common = shared;
            }
            prefixes.add(String.join(".", Arrays.copyOf(first, Math.max(common, Math.min(2, first.length)))));
        }
        return List.copyOf(prefixes);
    }

    /** The package of a class name, or {@code null} for the default package. */
    public static String packageOf(String className) {
        if (className == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot <= 0 ? null : className.substring(0, dot);
    }

    /** Whether a package belongs to one of BootUI's own modules. */
    public static boolean isBootUi(String name) {
        for (String bootUi : BOOTUI_PACKAGES) {
            if (name.equals(bootUi) || name.startsWith(bootUi + ".")) {
                return true;
            }
        }
        return false;
    }
}
