/**
 * Framework-neutral Hibernate/JPA mapping advisor: a bounded, on-demand scanner that runs a curated
 * registry of static Hibernate best-practice rules against the host application's mapped entities.
 *
 * <p>Plain Java (BootUI core DTOs + the neutral {@code jakarta.persistence} JCP standard); adapters
 * supply already-resolved {@link io.github.jdubois.bootui.engine.hibernate.EntityDiscovery} (entities
 * read via {@link io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader} plus any
 * framework-specific repositories) and configuration through a property-lookup + active-profiles seam,
 * then wire {@link io.github.jdubois.bootui.engine.hibernate.HibernateScanner} via an {@code @Bean}
 * factory / {@code @Produces} method. {@link io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader}
 * is the only class here that touches {@code jakarta.persistence}, and it is invoked only by the adapter
 * behind a JPA-presence gate.
 */
package io.github.jdubois.bootui.engine.hibernate;
