package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.engine.hibernate.HibernateQueryEvidence;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryMethodModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryModel;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;

/** Existing JPA factory metadata only. No repository product or query is ever invoked. */
final class SpringHibernateRepositoryDiscovery {
    private static final String JPA = "org.springframework.data.jpa.repository.";

    private SpringHibernateRepositoryDiscovery() {}

    static List<HibernateRepositoryModel> discover(ListableBeanFactory beans, List<String> errors) {
        if (beans == null) return List.of();
        List<HibernateRepositoryModel> repositories = new ArrayList<>();
        try {
            Class<?> factoryType =
                    Class.forName("org.springframework.data.repository.core.support.RepositoryFactoryInformation");
            Class<?> jpaEntityInformation =
                    Class.forName("org.springframework.data.jpa.repository.support.JpaEntityInformation");
            Class<?> informationType = Class.forName("org.springframework.data.repository.core.RepositoryInformation");
            if (!(beans instanceof ConfigurableListableBeanFactory configurable)) {
                errors.add("REPOSITORY_METADATA_UNAVAILABLE");
                return List.of();
            }
            for (String name : beans.getBeanNamesForType(factoryType, true, false)) {
                String singletonName = name.startsWith("&") ? name.substring(1) : name;
                Object factory = configurable.getSingleton(singletonName);
                if (factory == null || !factoryType.isInstance(factory)) {
                    errors.add("REPOSITORY_METADATA_UNAVAILABLE");
                    continue;
                }
                try {
                    Object entityInformation =
                            factoryType.getMethod("getEntityInformation").invoke(factory);
                    if (!jpaEntityInformation.isInstance(entityInformation)) continue;
                    Object information =
                            factoryType.getMethod("getRepositoryInformation").invoke(factory);
                    Class<?> repository = (Class<?>)
                            informationType.getMethod("getRepositoryInterface").invoke(information);
                    Class<?> domain = (Class<?>)
                            informationType.getMethod("getDomainType").invoke(information);
                    Method isQuery = informationType.getMethod("isQueryMethod", Method.class);
                    Method isCustom = informationType.getMethod("isCustomMethod", Method.class);
                    Method isBaseMethod = informationType.getMethod("isBaseClassMethod", Method.class);
                    boolean customSave = false;
                    boolean standardSave = false;
                    List<HibernateRepositoryMethodModel> methods = new ArrayList<>();
                    for (Method method : repository.getMethods()) {
                        if (method.getName().equals("save") || method.getName().equals("saveAll")) {
                            customSave |=
                                    method.isDefault() || Boolean.TRUE.equals(isCustom.invoke(information, method));
                            standardSave |= Boolean.TRUE.equals(isBaseMethod.invoke(information, method));
                        }
                        if (method.isDefault() || !Boolean.TRUE.equals(isQuery.invoke(information, method))) continue;
                        methods.add(readMethod(repository, domain, method, information));
                    }
                    Class<?> base = (Class<?>)
                            informationType.getMethod("getRepositoryBaseClass").invoke(information);
                    String entityInformationType = entityInformation.getClass().getName();
                    repositories.add(
                            new HibernateRepositoryModel(
                                    repository.getName(),
                                    domain,
                                    methods,
                                    standardSave
                                            && !customSave
                                            && "org.springframework.data.jpa.repository.support.SimpleJpaRepository"
                                                    .equals(base.getName())
                                            && (entityInformationType.equals(
                                                            "org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation")
                                                    || entityInformationType.equals(
                                                            "org.springframework.data.jpa.repository.support.JpaPersistableEntityInformation"))));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    errors.add("REPOSITORY_METADATA_UNAVAILABLE");
                }
            }
        } catch (ClassNotFoundException ex) {
            return List.of();
        } catch (RuntimeException | LinkageError ex) {
            errors.add("REPOSITORY_METADATA_UNAVAILABLE");
        }
        return List.copyOf(repositories);
    }

    static HibernateRepositoryMethodModel readMethod(Class<?> repository, Class<?> domain, Method method) {
        try {
            Object metadata = Class.forName(
                            "org.springframework.data.repository.core.support.AbstractRepositoryMetadata")
                    .getMethod("getMetadata", Class.class)
                    .invoke(null, repository);
            return readMethod(repository, domain, method, metadata);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("JPA repository metadata is unavailable.", ex);
        }
    }

    private static HibernateRepositoryMethodModel readMethod(
            Class<?> repository, Class<?> domain, Method method, Object metadata) {
        MergedAnnotations annotations = MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
        MergedAnnotation<Annotation> query = annotations.get(JPA + "Query");
        MergedAnnotation<Annotation> nativeQuery = annotations.get(JPA + "NativeQuery");
        MergedAnnotation<Annotation> modifying = annotations.get(JPA + "Modifying");
        MergedAnnotation<Annotation> graph = annotations.get(JPA + "EntityGraph");
        boolean named = presentString(query, "name")
                || presentString(query, "countName")
                || presentString(query, "countProjection")
                || presentString(nativeQuery, "sqlResultSetMapping");
        Class<?> rewriter =
                query.isPresent() ? query.getValue("queryRewriter", Class.class).orElse(null) : null;
        boolean rewriting = rewriter != null && !rewriter.getName().endsWith("$IdentityQueryRewriter");
        Class<?> element = returnElement(repository, method);
        Boolean limitInMemory = null;
        MergedAnnotation<Annotation> hints = annotations.get(JPA + "QueryHints");
        if (hints.isPresent()) {
            for (MergedAnnotation<Annotation> hint : hints.getAnnotationArray("value", Annotation.class)) {
                if ("org.hibernate.limitInMemory".equals(hint.getString("name"))) {
                    String value = hint.getString("value");
                    limitInMemory = "true".equalsIgnoreCase(value)
                            ? Boolean.TRUE
                            : "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
                }
            }
        }
        List<String> bindings = collectionParameterBindings(metadata, method);
        HibernateQueryEvidence evidence = new HibernateQueryEvidence(
                true,
                query.isPresent() || nativeQuery.isPresent(),
                false,
                named,
                rewriting || method.getTypeParameters().length > 0,
                element,
                graph.isPresent(),
                graph.isPresent() ? graph.getValue("type").map(Object::toString).orElse(null) : null,
                limitInMemory,
                bindings);
        return new HibernateRepositoryMethodModel(
                repository.getName(),
                method.getName(),
                domain,
                method.getReturnType(),
                query.isPresent() ? query.getString("value") : null,
                nativeQuery.isPresent() || query.isPresent() && query.getBoolean("nativeQuery"),
                query.isPresent() ? query.getString("countQuery") : null,
                Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type.getName().equals("org.springframework.data.domain.Pageable")),
                modifying.isPresent(),
                modifying.isPresent() && modifying.getBoolean("clearAutomatically"),
                modifying.isPresent() && modifying.getBoolean("flushAutomatically"),
                Arrays.asList(method.getParameterTypes()),
                evidence);
    }

    private static List<String> collectionParameterBindings(Object metadata, Method method) {
        try {
            Class<?> metadataType = Class.forName("org.springframework.data.repository.core.RepositoryMetadata");
            Class<?> sourceType = Class.forName("org.springframework.data.repository.query.ParametersSource");
            Object source =
                    sourceType.getMethod("of", metadataType, Method.class).invoke(null, metadata, method);
            Class<?> parametersType = Class.forName("org.springframework.data.jpa.repository.query.JpaParameters");
            Object parameters = parametersType.getConstructor(sourceType).newInstance(source);
            Iterable<?> bindable = (Iterable<?>)
                    parametersType.getMethod("getBindableParameters").invoke(parameters);
            Class<?> parameterType = Class.forName("org.springframework.data.repository.query.Parameter");
            Method getType = parameterType.getMethod("getType");
            Method getName = parameterType.getMethod("getName");
            List<String> bindings = new ArrayList<>();
            int position = 0;
            for (Object parameter : bindable) {
                position++;
                Class<?> type = (Class<?>) getType.invoke(parameter);
                if (!type.isArray() && !Collection.class.isAssignableFrom(type)) continue;
                bindings.add("?" + position);
                Optional<?> name = (Optional<?>) getName.invoke(parameter);
                if (name.orElse(null) instanceof String value) bindings.add(":" + value);
            }
            return List.copyOf(bindings);
        } catch (ReflectiveOperationException ex) {
            // The caller records controlled missing metadata, never guessed placeholder positions.
            throw new IllegalStateException("JPA bindable parameter metadata is unavailable.", ex);
        }
    }

    private static boolean presentString(MergedAnnotation<Annotation> annotation, String attribute) {
        return annotation.isPresent()
                && annotation
                        .getValue(attribute, String.class)
                        .filter(value -> !value.isBlank())
                        .isPresent();
    }

    private static Class<?> returnElement(Class<?> repository, Method method) {
        ResolvableType type = ResolvableType.forMethodReturnType(method, repository);
        if (method.getReturnType().isArray()) return method.getReturnType().getComponentType();
        if (Iterable.class.isAssignableFrom(method.getReturnType()))
            return type.as(Iterable.class).getGeneric(0).resolve();
        if (java.util.stream.Stream.class.isAssignableFrom(method.getReturnType()))
            return type.as(java.util.stream.Stream.class).getGeneric(0).resolve();
        if (java.util.Optional.class.isAssignableFrom(method.getReturnType()))
            return type.as(java.util.Optional.class).getGeneric(0).resolve();
        return type.resolve();
    }
}
