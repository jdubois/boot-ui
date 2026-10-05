package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryModel;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.hibernate.Version;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

/** Optional Hibernate binding. Factory resolution and metadata reads happen only on explicit scans. */
public final class SpringHibernateAdvisorObservationSource implements HibernateAdvisorObservationSource {
    private final ListableBeanFactory beans;
    private final Environment environment;

    public SpringHibernateAdvisorObservationSource(ListableBeanFactory beans, Environment environment) {
        this.beans = beans;
        this.environment = environment;
    }

    @Override
    public HibernateAdvisorObservation observe() {
        List<HibernateObservationDiagnostic> diagnostics = new ArrayList<>();
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            String[] factoryNames =
                    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beans, EntityManagerFactory.class, true, false);
            for (int index = 0; index < factoryNames.length; index++) {
                String label = "unit-" + (index + 1);
                EntityManagerFactory emf = null;
                try {
                    // Resolving a lazy factory may fail. Keep that failure inside this unit's boundary.
                    emf = beans.getBean(factoryNames[index], EntityManagerFactory.class);
                    SessionFactoryImplementor factory = emf.unwrap(SessionFactoryImplementor.class);
                    if (factory == null) throw new IllegalStateException();
                    if (!seen.add(factory)) continue;
                    String name = unitName(emf, factory);
                    if (name != null) {
                        String proposed = HibernatePersistenceUnitObservation.safeLabel(name);
                        if (units.stream().noneMatch(unit -> unit.label().equals(proposed))) label = proposed;
                    }
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
                            null));
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
                                null));
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            diagnostics.add(new HibernateObservationDiagnostic(
                    "application", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
        }
        List<String> repositoryErrors = new ArrayList<>();
        List<HibernateRepositoryModel> repositories =
                SpringHibernateRepositoryDiscovery.discover(beans, repositoryErrors);
        if (!repositoryErrors.isEmpty())
            diagnostics.add(new HibernateObservationDiagnostic(
                    "application", HibernateObservationDiagnostic.Reason.REPOSITORY_METADATA_UNAVAILABLE));
        for (HibernateRepositoryModel repository : repositories) {
            List<Integer> matchingUnits = new ArrayList<>();
            for (int i = 0; i < units.size(); i++) {
                if (units.get(i).entities().stream()
                        .anyMatch(entity -> repository.domainType().equals(entity.javaType()))) {
                    matchingUnits.add(i);
                }
            }
            if (matchingUnits.size() != 1) {
                diagnostics.add(new HibernateObservationDiagnostic(
                        "application",
                        matchingUnits.size() > 1
                                ? HibernateObservationDiagnostic.Reason.AMBIGUOUS_REPOSITORY_UNIT
                                : HibernateObservationDiagnostic.Reason.REPOSITORY_METADATA_UNAVAILABLE));
                continue;
            }
            int index = matchingUnits.get(0);
            HibernatePersistenceUnitObservation unit = units.get(index);
            List<HibernateRepositoryModel> assigned = new ArrayList<>(unit.repositories());
            assigned.add(repository);
            units.set(
                    index,
                    new HibernatePersistenceUnitObservation(
                            unit.unitKey(),
                            unit.label(),
                            unit.entities(),
                            assigned,
                            unit.hibernateVersion(),
                            unit.settings(),
                            unit.enhancementVerified()));
        }
        return new HibernateAdvisorObservation(units, applicationFacts(), diagnostics);
    }

    private HibernateApplicationFacts applicationFacts() {
        Boolean initializer = hasBean("org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer");
        Boolean deferred = Boolean.TRUE.equals(initializer)
                ? environment.getProperty("spring.jpa.defer-datasource-initialization", Boolean.class, false)
                : Boolean.FALSE.equals(initializer) ? false : null;
        return new HibernateApplicationFacts(
                List.of(environment.getActiveProfiles()),
                deferred,
                logger("org.hibernate.SQL", false),
                logger("org.hibernate.orm.jdbc.bind", true),
                false);
    }

    private static String unitName(EntityManagerFactory emf, SessionFactoryImplementor factory) {
        try {
            Class<?> info = Class.forName(
                    "org.springframework.orm.jpa.EntityManagerFactoryInfo",
                    false,
                    SpringHibernateAdvisorObservationSource.class.getClassLoader());
            if (info.isInstance(emf)) {
                Object name = info.getMethod("getPersistenceUnitName").invoke(emf);
                if (name instanceof String value) return value;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // A scan-local label is preferable to guessed default-unit attribution.
        }
        try {
            return factory.getName();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Boolean hasBean(String typeName) {
        if (beans == null) return null;
        try {
            Class<?> type = Class.forName(typeName, false, getClass().getClassLoader());
            return beans.getBeanNamesForType(type, true, false).length > 0;
        } catch (ClassNotFoundException ex) {
            return false;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Boolean logger(String name, boolean traceOnly) {
        // Logger reads do not initialize repository products or reveal messages/parameters.
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(name);
        return traceOnly ? logger.isTraceEnabled() : logger.isDebugEnabled();
    }
}
