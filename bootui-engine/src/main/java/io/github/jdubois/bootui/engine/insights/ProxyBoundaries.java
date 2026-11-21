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
     * @param cacheNames the caches whose access must precede the method; empty for {@code sync = true}, whose miss
     *     is recorded after the loader runs, or a {@code condition}, which can skip the cache entirely
     * @param async whether it is {@code @Async}
     * @param cacheManager the explicitly selected manager, or null for the adapter's proven default
     * @param cacheUnobservable why the method's cache access cannot prove absence, or null for a synchronous path
     */
    record Boundary(
            boolean transactional,
            Set<String> cacheNames,
            boolean async,
            String cacheManager,
            String cacheUnobservable) {

        public static final Boundary NONE = new Boundary(false, Set.of(), false);

        public Boundary(boolean transactional, Set<String> cacheNames, boolean async) {
            this(transactional, cacheNames, async, null, null);
        }

        public Boundary {
            cacheNames = cacheNames == null ? Set.of() : Set.copyOf(cacheNames);
        }

        /** Whether the method declares any judgeable boundary. */
        public boolean any() {
            return transactional || async || !cacheNames.isEmpty() || cacheUnobservable != null;
        }
    }
}
