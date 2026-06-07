package io.github.jdubois.bootui.autoconfigure.springadvisor;

import java.util.List;

/**
 * Small read-only value types describing the host application's wired beans, captured during a
 * single Spring Advisor scan.
 */
final class SpringAdvisorModel {

    private SpringAdvisorModel() {}

    /** A managed bean of interest: its bean name and whether it is marked {@code @Primary}. */
    record BeanRef(String name, boolean primary) {}

    /** Counts the beans in {@code refs} that are marked primary. */
    static long primaryCount(List<BeanRef> refs) {
        return refs.stream().filter(BeanRef::primary).count();
    }
}
