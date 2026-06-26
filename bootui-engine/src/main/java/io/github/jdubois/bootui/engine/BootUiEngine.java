package io.github.jdubois.bootui.engine;

/**
 * Marker interface for BootUI engine components: framework-neutral services and advisor engines.
 *
 * <p>It declares no methods. It exists so the {@code io.github.jdubois.bootui.engine} module always
 * compiles to at least one type (keeping source/javadoc packaging and ArchUnit analysis well defined)
 * and to anchor the package's documentation. Engine services are introduced as vertical slices are
 * extracted from the Spring adapter; they may, but are not required to, implement this marker.
 */
public interface BootUiEngine {}
