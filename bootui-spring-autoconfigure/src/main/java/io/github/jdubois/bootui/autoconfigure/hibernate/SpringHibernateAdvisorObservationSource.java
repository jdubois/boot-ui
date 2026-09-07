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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.Version;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** Optional Hibernate binding. Factory resolution and metadata reads happen only on explicit scans. */
public final class SpringHibernateAdvisorObservationSource implements HibernateAdvisorObservationSource {
    private static final String OSIV_INTERCEPTOR =
            "org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor";
    private static final String OSIV_FILTER = "org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter";
    private static final int MAX_BEAN_ENTRIES = 4096;
    private static final int MAX_REGISTRATIONS = 128;
    private static final int MAX_INTERCEPTORS = 128;
    private static final int MAX_WRAPPER_DEPTH = 4;

    private final ListableBeanFactory beans;
    private final Environment environment;
    private final ApplicationContext applicationContext;
    private final boolean servlet;

    public SpringHibernateAdvisorObservationSource(
            ListableBeanFactory beans, Environment environment, ApplicationContext applicationContext) {
        this.beans = beans;
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.servlet = SpringHibernatePropertyLookup.isServletWebApplication(applicationContext);
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
        HibernateApplicationFacts.OpenInView osiv = HibernateApplicationFacts.OpenInView.NOT_APPLICABLE;
        if (servlet) {
            Boolean interceptor = osivInterceptorRegistered();
            Boolean filter = osivFilterRegistered();
            osiv = Boolean.TRUE.equals(interceptor) || Boolean.TRUE.equals(filter)
                    ? HibernateApplicationFacts.OpenInView.ENABLED
                    : Boolean.FALSE.equals(interceptor)
                                    && Boolean.FALSE.equals(filter)
                                    && Boolean.FALSE.equals(hasBean(OSIV_INTERCEPTOR))
                                    && Boolean.FALSE.equals(hasBean(OSIV_FILTER))
                                    && Boolean.FALSE.equals(
                                            hasBean("org.springframework.boot.web.servlet.FilterRegistrationBean"))
                                    && "false".equalsIgnoreCase(environment.getProperty("spring.jpa.open-in-view"))
                            ? HibernateApplicationFacts.OpenInView.DISABLED
                            : HibernateApplicationFacts.OpenInView.UNKNOWN;
        }

        Boolean initializer = hasBean("org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer");
        Boolean deferred = Boolean.TRUE.equals(initializer)
                ? environment.getProperty("spring.jpa.defer-datasource-initialization", Boolean.class, false)
                : Boolean.FALSE.equals(initializer) ? false : null;
        return new HibernateApplicationFacts(
                List.of(environment.getActiveProfiles()),
                osiv,
                deferred,
                logger("org.hibernate.SQL", false),
                logger("org.hibernate.orm.jdbc.bind", true),
                false);
    }

    private Boolean osivInterceptorRegistered() {
        try {
            ListableBeanFactory candidate = beans;
            if (candidate instanceof ConfigurableApplicationContext context) candidate = context.getBeanFactory();
            if (!(candidate instanceof ConfigurableListableBeanFactory registry)) return null;
            // This upper bound can count a definition and its singleton twice; prefer unknown to a large type search.
            if ((long) registry.getBeanDefinitionCount() + registry.getSingletonCount() > MAX_BEAN_ENTRIES) return null;
            Class<?> mappingType = optionalType("org.springframework.web.servlet.handler.AbstractHandlerMapping");
            Class<?> osivType = optionalType(OSIV_INTERCEPTOR);
            Class<?> mappedType = optionalType("org.springframework.web.servlet.handler.MappedInterceptor");
            Class<?> adapterType =
                    optionalType("org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter");
            var adaptedInterceptors = mappingType.getMethod("getAdaptedInterceptors");
            // This framework getter is final in Spring 7. Do not call an application override if that changes.
            if (!Modifier.isFinal(adaptedInterceptors.getModifiers())) return null;
            String[] names = registry.getBeanNamesForType(mappingType, true, false);
            boolean unknown = names.length > MAX_REGISTRATIONS || registry.getParentBeanFactory() != null;
            for (int index = 0; index < Math.min(names.length, MAX_REGISTRATIONS); index++) {
                try {
                    // Never resolve a lazy/prototype mapping or a FactoryBean product during a scan.
                    Object mapping = registry.getSingleton(names[index]);
                    if (!mappingType.isInstance(mapping)) {
                        unknown = true;
                        continue;
                    }
                    Object value = adaptedInterceptors.invoke(mapping);
                    if (!(value instanceof Object[] interceptors)) {
                        unknown = true;
                        continue;
                    }
                    unknown |= interceptors.length > MAX_INTERCEPTORS;
                    for (int i = 0; i < Math.min(interceptors.length, MAX_INTERCEPTORS); i++) {
                        Boolean registered = isOsivInterceptor(interceptors[i], osivType, mappedType, adapterType);
                        if (Boolean.TRUE.equals(registered)) return true;
                        unknown |= registered == null;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    // One unreadable mapping must not hide a later independently readable registration.
                    unknown = true;
                }
            }
            return unknown ? null : false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Boolean isOsivInterceptor(Object value, Class<?> osivType, Class<?> mappedType, Class<?> adapterType) {
        try {
            for (int depth = 0; depth < MAX_WRAPPER_DEPTH; depth++) {
                if (osivType.isInstance(value)) return true;
                if (mappedType.isInstance(value)) {
                    if (value.getClass() != mappedType) return null;
                    value = mappedType.getMethod("getInterceptor").invoke(value);
                } else if (adapterType.isInstance(value)) {
                    // A custom adapter may override callbacks without invoking its stored delegate.
                    if (value.getClass() != adapterType) return null;
                    // Spring 7 has no public unwrap getter. Read only this known, final delegate field;
                    // inaccessible modules or changed internals are unknown, never activation evidence.
                    var delegate = adapterType.getDeclaredField("requestInterceptor");
                    if (!delegate.trySetAccessible()) return null;
                    value = delegate.get(value);
                } else {
                    return false;
                }
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Boolean osivFilterRegistered() {
        try {
            Class<?> webContextType = optionalType("org.springframework.web.context.WebApplicationContext");
            if (!webContextType.isInstance(applicationContext)) return null;
            Object servletContext =
                    webContextType.getMethod("getServletContext").invoke(applicationContext);
            if (servletContext == null) return null;
            Class<?> contextType = optionalType("jakarta.servlet.ServletContext");
            Class<?> registrationType = optionalType("jakarta.servlet.FilterRegistration");
            Class<?> osivType = optionalType(OSIV_FILTER);
            Object value = contextType.getMethod("getFilterRegistrations").invoke(servletContext);
            if (!(value instanceof Map<?, ?> registrations)) return null;
            boolean unknown = false;
            int inspected = 0;
            for (Object registration : registrations.values()) {
                if (++inspected > MAX_REGISTRATIONS) return null;
                try {
                    Object className =
                            registrationType.getMethod("getClassName").invoke(registration);
                    if (!(className instanceof String name)) {
                        unknown = true;
                        continue;
                    }
                    if (!osivType.isAssignableFrom(optionalType(name))) continue;
                    Object urls =
                            registrationType.getMethod("getUrlPatternMappings").invoke(registration);
                    Object servlets =
                            registrationType.getMethod("getServletNameMappings").invoke(registration);
                    if ((urls instanceof Collection<?> urlMappings && !urlMappings.isEmpty())
                            || (servlets instanceof Collection<?> servletMappings && !servletMappings.isEmpty()))
                        return true;
                    unknown |= !(urls instanceof Collection<?>) || !(servlets instanceof Collection<?>);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    unknown = true;
                }
            }
            return unknown ? null : false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Class<?> optionalType(String name) throws ClassNotFoundException {
        return Class.forName(name, false, applicationContext.getClassLoader());
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
