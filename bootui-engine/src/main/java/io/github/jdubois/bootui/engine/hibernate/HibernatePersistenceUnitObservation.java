package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** A unit's mappings and defaults must always originate from the same native factory. */
public record HibernatePersistenceUnitObservation(
        String unitKey,
        String label,
        List<HibernateEntityModel> entities,
        List<HibernateRepositoryModel> repositories,
        String hibernateVersion,
        HibernateFactorySettings settings,
        Boolean enhancementVerified) {
    public HibernatePersistenceUnitObservation {
        label = safeLabel(label);
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
        settings = settings == null ? HibernateFactorySettings.unknown() : settings;
    }

    public static String safeLabel(String label) {
        return label != null && label.matches("[A-Za-z0-9_.-]{1,64}") ? label : "persistence-unit";
    }
}
