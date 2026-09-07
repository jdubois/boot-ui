package io.github.jdubois.bootui.quarkus.hibernate;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.hibernate.Version;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/** Instantiated exclusively by the ORM-capability-gated producer, not a CDI-discovered bean. */
public final class QuarkusHibernateAdvisorObservationSource implements HibernateAdvisorObservationSource {
    private final Instance<EntityManagerFactory> factories;
    private final Config config;

    public QuarkusHibernateAdvisorObservationSource(Instance<EntityManagerFactory> factories, Config config) {
        this.factories = factories;
        this.config = config;
    }

    @Override
    public HibernateAdvisorObservation observe() {
        List<HibernateObservationDiagnostic> diagnostics = new ArrayList<>();
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            int index = 0;
            for (Instance.Handle<EntityManagerFactory> handle : factories.handles()) {
                String label = "unit-" + (++index);
                EntityManagerFactory emf = null;
                try {
                    // Instance.iterator().next() creates the bean before a foreach body can catch failures.
                    // Handles enumerate metadata only. Do not close/destroy application-owned factories.
                    emf = handle.get();
                    SessionFactoryImplementor factory = emf.unwrap(SessionFactoryImplementor.class);
                    if (factory == null) throw new IllegalStateException();
                    if (!seen.add(factory)) continue;
                    String proposed = HibernatePersistenceUnitObservation.safeLabel(unitName(factory));
                    if (!"persistence-unit".equals(proposed)
                            && units.stream().noneMatch(unit -> unit.label().equals(proposed))) label = proposed;
                    EntityDiscovery entities = JpaMetamodelReader.readEntities(List.of(emf));
                    if (!entities.errors().isEmpty())
                        diagnostics.add(new HibernateObservationDiagnostic(
                                label, HibernateObservationDiagnostic.Reason.METAMODEL_UNAVAILABLE));
                    units.add(new HibernatePersistenceUnitObservation(
                            "unit-" + units.size(),
                            label,
                            entities.entities(),
                            List.of(),
                            Version.getVersionString(),
                            HibernateFactorySettingsReader.read(factory, label, diagnostics),
                            true));
                } catch (RuntimeException | LinkageError ex) {
                    diagnostics.add(new HibernateObservationDiagnostic(
                            label, HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
                    EntityDiscovery readable = JpaMetamodelReader.readEntities(emf == null ? List.of() : List.of(emf));
                    if (!readable.entities().isEmpty())
                        units.add(new HibernatePersistenceUnitObservation(
                                "unavailable-" + units.size(),
                                label,
                                readable.entities(),
                                List.of(),
                                null,
                                null,
                                true));
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            diagnostics.add(new HibernateObservationDiagnostic(
                    "application", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
        }
        List<String> profiles = Arrays.stream(config.getOptionalValue("quarkus.profile", String.class)
                        .orElse("")
                        .split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        HibernateApplicationFacts facts = new HibernateApplicationFacts(
                profiles,
                HibernateApplicationFacts.OpenInView.NOT_APPLICABLE,
                false,
                org.jboss.logging.Logger.getLogger("org.hibernate.SQL").isDebugEnabled(),
                org.jboss.logging.Logger.getLogger("org.hibernate.orm.jdbc.bind")
                        .isTraceEnabled(),
                config.getOptionalValue("bootui.internal.hibernate-panache-enhancement", Boolean.class)
                        .orElse(false));
        return new HibernateAdvisorObservation(units, facts, diagnostics);
    }

    private static String unitName(SessionFactoryImplementor factory) {
        try {
            return factory.getName();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
