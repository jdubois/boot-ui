package io.github.jdubois.bootui.engine.hibernate;

/** Controlled reasons only: no exception, property, query or connection text. */
public record HibernateObservationDiagnostic(String unitLabel, Reason reason) {
    public enum Reason {
        FACTORY_UNAVAILABLE,
        METAMODEL_UNAVAILABLE,
        FACTORY_SETTING_UNAVAILABLE,
        REPOSITORY_METADATA_UNAVAILABLE,
        AMBIGUOUS_REPOSITORY_UNIT,
        SOURCE_UNAVAILABLE
    }

    public HibernateObservationDiagnostic {
        unitLabel = HibernatePersistenceUnitObservation.safeLabel(unitLabel);
    }
}
