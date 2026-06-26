package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryMethodModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryModel;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.persistence.EntityManagerFactory;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring adapter for Hibernate entity/repository discovery.
 *
 * <p>The framework-neutral {@link io.github.jdubois.bootui.engine.hibernate.HibernateScanner} consumes
 * an already-resolved {@link EntityDiscovery}. This adapter composes that discovery from the JPA
 * metamodel (via the engine {@link JpaMetamodelReader}, the sole {@code jakarta.persistence} reader)
 * and Spring Data repository metadata (read reflectively so the optional Spring Data types are never
 * hard-referenced).</p>
 */
public final class SpringHibernateDiscovery {

    private SpringHibernateDiscovery() {}

    public static EntityDiscovery discover(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<ListableBeanFactory> beanFactories) {
        EntityDiscovery entityDiscovery = discoverEntities(entityManagerFactories);
        List<String> errors = new ArrayList<>(entityDiscovery.errors());
        List<HibernateRepositoryModel> repositories = discoverRepositories(beanFactories, errors);
        return new EntityDiscovery(entityDiscovery.entities(), repositories, errors);
    }

    private static EntityDiscovery discoverEntities(ObjectProvider<EntityManagerFactory> entityManagerFactories) {
        List<EntityManagerFactory> factories;
        try {
            factories = entityManagerFactories.stream().toList();
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
        return JpaMetamodelReader.readEntities(factories);
    }

    private static List<HibernateRepositoryModel> discoverRepositories(
            ObjectProvider<ListableBeanFactory> beanFactories, List<String> errors) {
        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        if (beanFactory == null) {
            return List.of();
        }
        Class<?> factoryInformationType =
                classForName("org.springframework.data.repository.core.support.RepositoryFactoryInformation");
        Class<?> repositoryInformationType =
                classForName("org.springframework.data.repository.core.RepositoryInformation");
        if (factoryInformationType == null || repositoryInformationType == null) {
            return List.of();
        }
        List<HibernateRepositoryModel> repositories = new ArrayList<>();
        try {
            String[] beanNames = beanFactory.getBeanNamesForType(factoryInformationType);
            Method getRepositoryInformation = factoryInformationType.getMethod("getRepositoryInformation");
            Method getRepositoryInterface = repositoryInformationType.getMethod("getRepositoryInterface");
            Method getDomainType = repositoryInformationType.getMethod("getDomainType");
            Method isQueryMethod = repositoryInformationType.getMethod("isQueryMethod", Method.class);
            for (String beanName : beanNames) {
                try {
                    Object factoryInformation = beanFactory.getBean(beanName, factoryInformationType);
                    Object repositoryInformation = getRepositoryInformation.invoke(factoryInformation);
                    Class<?> repositoryInterface = (Class<?>) getRepositoryInterface.invoke(repositoryInformation);
                    Class<?> domainType = (Class<?>) getDomainType.invoke(repositoryInformation);
                    List<HibernateRepositoryMethodModel> methods =
                            queryMethods(repositoryInterface, domainType, repositoryInformation, isQueryMethod);
                    repositories.add(new HibernateRepositoryModel(
                            repositoryInterface == null ? strip(beanName) : repositoryInterface.getName(),
                            domainType,
                            methods));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    errors.add("Repository " + strip(beanName) + ": " + safeMessage(ex));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            errors.add("Spring Data repositories: " + safeMessage(ex));
        }
        return repositories;
    }

    private static List<HibernateRepositoryMethodModel> queryMethods(
            Class<?> repositoryInterface, Class<?> domainType, Object repositoryInformation, Method isQueryMethod)
            throws ReflectiveOperationException {
        if (repositoryInterface == null) {
            return List.of();
        }
        List<HibernateRepositoryMethodModel> methods = new ArrayList<>();
        for (Method method : repositoryInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class
                    || !Boolean.TRUE.equals(isQueryMethod.invoke(repositoryInformation, method))) {
                continue;
            }
            QueryAnnotation query = readQueryAnnotation(method);
            ModifyingAnnotation modifying = readModifyingAnnotation(method);
            String queryValue = query == null ? null : query.value();
            String countQueryValue = query == null ? null : query.countQuery();
            boolean nativeQuery = query != null && query.nativeQuery();
            methods.add(new HibernateRepositoryMethodModel(
                    repositoryInterface.getName(),
                    method.getName(),
                    domainType,
                    method.getReturnType(),
                    queryValue,
                    nativeQuery,
                    countQueryValue,
                    hasPageableParameter(method),
                    modifying != null,
                    modifying != null && modifying.clearAutomatically(),
                    modifying != null && modifying.flushAutomatically(),
                    Arrays.asList(method.getParameterTypes())));
        }
        return methods;
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static QueryAnnotation readQueryAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (!"org.springframework.data.jpa.repository.Query"
                    .equals(annotation.annotationType().getName())) {
                continue;
            }
            String value = stringAttribute(annotation, "value");
            String nativeQuery = stringAttribute(annotation, "nativeQuery");
            String countQuery = stringAttribute(annotation, "countQuery");
            boolean hasValue = value != null && !value.isBlank();
            return new QueryAnnotation(value, Boolean.parseBoolean(nativeQuery), countQuery, hasValue);
        }
        return null;
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static ModifyingAnnotation readModifyingAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (!"org.springframework.data.jpa.repository.Modifying"
                    .equals(annotation.annotationType().getName())) {
                continue;
            }
            String clear = stringAttribute(annotation, "clearAutomatically");
            String flush = stringAttribute(annotation, "flushAutomatically");
            return new ModifyingAnnotation(Boolean.parseBoolean(clear), Boolean.parseBoolean(flush));
        }
        return null;
    }

    private static String stringAttribute(Annotation annotation, String attribute) {
        try {
            Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static boolean hasPageableParameter(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if ("org.springframework.data.domain.Pageable".equals(parameterType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private record QueryAnnotation(String value, boolean nativeQuery, String countQuery, boolean hasValue) {}

    private record ModifyingAnnotation(boolean clearAutomatically, boolean flushAutomatically) {}
}
