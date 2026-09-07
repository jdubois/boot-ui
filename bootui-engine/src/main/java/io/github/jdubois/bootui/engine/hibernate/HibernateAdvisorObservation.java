package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

public record HibernateAdvisorObservation(
        List<HibernatePersistenceUnitObservation> units,
        HibernateApplicationFacts application,
        List<HibernateObservationDiagnostic> diagnostics) {
    public HibernateAdvisorObservation {
        units = List.copyOf(units);
        application = application == null ? HibernateApplicationFacts.unknown(List.of()) : application;
        diagnostics = List.copyOf(diagnostics);
    }
}
