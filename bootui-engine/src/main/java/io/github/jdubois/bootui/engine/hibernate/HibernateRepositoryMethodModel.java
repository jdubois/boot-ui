package io.github.jdubois.bootui.engine.hibernate;

import java.util.Collection;
import java.util.List;

public record HibernateRepositoryMethodModel(
        String repositoryInterface,
        String methodName,
        Class<?> domainType,
        Class<?> returnType,
        String query,
        boolean nativeQuery,
        String countQuery,
        boolean hasPageableParameter,
        boolean modifying,
        boolean modifyingClearsAutomatically,
        boolean modifyingFlushesAutomatically,
        List<Class<?>> parameterTypes,
        HibernateQueryEvidence evidence) {

    public HibernateRepositoryMethodModel(
            String repositoryInterface,
            String methodName,
            Class<?> domainType,
            Class<?> returnType,
            String query,
            boolean nativeQuery,
            String countQuery,
            boolean hasPageableParameter,
            boolean modifying,
            boolean modifyingClearsAutomatically,
            boolean modifyingFlushesAutomatically,
            List<Class<?>> parameterTypes) {
        this(
                repositoryInterface,
                methodName,
                domainType,
                returnType,
                query,
                nativeQuery,
                countQuery,
                hasPageableParameter,
                modifying,
                modifyingClearsAutomatically,
                modifyingFlushesAutomatically,
                parameterTypes,
                HibernateQueryEvidence.unknown());
    }

    public HibernateRepositoryMethodModel {
        parameterTypes = List.copyOf(parameterTypes);
        evidence = evidence == null ? HibernateQueryEvidence.unknown() : evidence;
    }

    boolean hasCollectionParameter() {
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.isArray() || Collection.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    boolean hasQuery() {
        return query != null && !query.isBlank();
    }

    boolean hasCountQuery() {
        return countQuery != null && !countQuery.isBlank();
    }

    boolean returnsStream() {
        return returnType != null && java.util.stream.Stream.class.isAssignableFrom(returnType);
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    boolean returnsPage() {
        return returnType != null && "org.springframework.data.domain.Page".equals(returnType.getName());
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    boolean returnsSlice() {
        return returnType != null && "org.springframework.data.domain.Slice".equals(returnType.getName());
    }

    boolean returnsMultiple() {
        if (returnType == null) {
            return false;
        }
        return returnsStream()
                || returnsPage()
                || returnsSlice()
                || returnType.isArray()
                || Collection.class.isAssignableFrom(returnType);
    }

    boolean isDerivedDeleteMethod() {
        return methodName != null && (methodName.startsWith("deleteBy") || methodName.startsWith("removeBy"));
    }

    String description() {
        return repositoryInterface + "#" + methodName;
    }
}
