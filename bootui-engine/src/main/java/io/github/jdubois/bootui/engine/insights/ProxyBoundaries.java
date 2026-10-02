package io.github.jdubois.bootui.engine.insights;

import java.util.Set;

/**
 * Resolves the proxy boundaries an application frame's method declares ({@code docs/PLAN-v2.md} §5.12): whether it is
 * {@code @Transactional} with a propagation that requires a transaction, {@code @Cacheable} for which caches, and
 * {@code @Async}. A Spring adapter implements it with Spring's merged-annotation lookup, so annotations inherited from
 * interfaces and declared on the class count; implementations cache each frame's answer.
 */
public interface ProxyBoundaries {

    /**
     * The boundaries of the method a frame such as {@code com.example.OrderService.place(OrderService.java:42)} names,
     * {@link Boundary#NONE} when it declares none, or {@code null} when it cannot be resolved, such as an unknown class
     * or overloads that declare different boundaries.
     */
    Boundary resolve(String frame);

    /** Why a proxy bypass cannot be judged in this application, such as AspectJ weaving, or {@code null}. */
    default String notApplicable() {
        return null;
    }

    /**
     * What a method's proxy would apply.
     *
     * @param transactional whether it is {@code @Transactional} with a propagation that requires a transaction
     * @param cacheNames the caches it is {@code @Cacheable} for
     * @param async whether it is {@code @Async}
     */
    record Boundary(boolean transactional, Set<String> cacheNames, boolean async) {

        public static final Boundary NONE = new Boundary(false, Set.of(), false);

        public Boundary {
            cacheNames = cacheNames == null ? Set.of() : Set.copyOf(cacheNames);
        }

        /** Whether the method declares any boundary. */
        public boolean any() {
            return transactional || async || !cacheNames.isEmpty();
        }
    }
}
