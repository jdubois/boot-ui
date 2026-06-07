package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.List;

/**
 * Small read-only value types describing the host application's wired beans, captured during a
 * single Spring Advisor scan.
 */
final class SpringModel {

    private SpringModel() {}

    /** A managed bean of interest: its bean name and whether it is marked {@code @Primary}. */
    record BeanRef(String name, boolean primary) {}

    /** A {@code CacheManager} bean: its name and the resolved implementation class name (may be null). */
    record CacheManagerRef(String name, String className) {}

    /** Counts the beans in {@code refs} that are marked primary. */
    static long primaryCount(List<BeanRef> refs) {
        return refs.stream().filter(BeanRef::primary).count();
    }

    /** Whether any bean in {@code refs} carries the conventional name {@code names}. */
    static boolean hasName(List<BeanRef> refs, String... names) {
        for (BeanRef ref : refs) {
            for (String name : names) {
                if (name.equals(ref.name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
