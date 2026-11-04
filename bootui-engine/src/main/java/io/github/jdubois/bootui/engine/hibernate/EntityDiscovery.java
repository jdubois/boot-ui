package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

public record EntityDiscovery(
        List<HibernateEntityModel> entities,
        List<HibernateRepositoryModel> repositories,
        List<String> errors,
        boolean knownAbsent) {

    public EntityDiscovery(
            List<HibernateEntityModel> entities, List<HibernateRepositoryModel> repositories, List<String> errors) {
        this(entities, repositories, errors, false);
    }

    public EntityDiscovery {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
        errors = List.copyOf(errors);
    }

    public static EntityDiscovery empty(String reason) {
        return new EntityDiscovery(List.of(), List.of(), List.of(reason == null ? "Unavailable." : reason));
    }

    /** A confirmed absence of the optional JPA integration, not a discovery failure. */
    public static EntityDiscovery absent(String reason) {
        return new EntityDiscovery(List.of(), List.of(), List.of(reason == null ? "Unavailable." : reason), true);
    }
}
