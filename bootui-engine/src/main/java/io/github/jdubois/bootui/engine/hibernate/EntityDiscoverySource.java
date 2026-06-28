package io.github.jdubois.bootui.engine.hibernate;

/**
 * A framework-neutral, JPA-free handle to the host application's mapped-entity discovery.
 *
 * <p>The engine {@link HibernateScanner} consumes an already-resolved {@link EntityDiscovery} through a
 * {@link java.util.function.Supplier}; this interface is the CDI-injectable seam an adapter implements so
 * that the {@code jakarta.persistence} types it reads stay confined to the adapter. Crucially the
 * interface itself references only the pure-JDK {@link EntityDiscovery} record, never
 * {@code jakarta.persistence}, so an always-on bean can inject it (e.g. via {@code Instance}) and decide
 * whether a discovery source is present <em>without</em> linking the optional JPA types — the Quarkus
 * adapter gates the implementing producer on the {@code quarkus-hibernate-orm} capability (R2).</p>
 */
@FunctionalInterface
public interface EntityDiscoverySource {

    /**
     * Discovers the mapped entities (and, where the adapter supports it, repositories) currently visible
     * to the host application. Implementations must be fail-soft: a discovery failure should surface as an
     * {@link EntityDiscovery} carrying errors (or {@link EntityDiscovery#empty(String)}) rather than
     * propagating, so the advisor renders a DISABLED report instead of failing the request.
     */
    EntityDiscovery discover();
}
