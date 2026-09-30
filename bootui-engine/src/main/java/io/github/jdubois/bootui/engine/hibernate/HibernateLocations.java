package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;

/**
 * Locations for Hibernate findings about one mapped code element: an entity class, the Java member behind a mapped
 * attribute, or a repository method. The JPA metamodel and reflection carry no line numbers, so these locations
 * are at class or member precision; the scan fills in the recorded source file and the local path afterwards.
 */
final class HibernateLocations {

    private HibernateLocations() {}

    /** The entity class, or {@code null} when the entity has no Java type. */
    static AdvisorViolationLocationDto of(HibernateEntityModel entity) {
        if (entity == null || entity.javaType() == null) return null;
        return location(entity.javaType().getName(), null, null);
    }

    /** The field or getter behind a mapped attribute, declared on {@link HibernateAttributeModel#entityName()}. */
    static AdvisorViolationLocationDto of(HibernateAttributeModel attribute) {
        if (attribute == null) return null;
        return location(
                attribute.entityName(),
                attribute.javaMemberName(),
                attribute.fieldMember() ? AdvisorViolationLocationDto.FIELD : AdvisorViolationLocationDto.METHOD);
    }

    /** The repository method a query finding concerns. */
    static AdvisorViolationLocationDto of(HibernateRepositoryMethodModel method) {
        if (method == null) return null;
        return location(method.repositoryInterface(), method.methodName(), AdvisorViolationLocationDto.METHOD);
    }

    private static AdvisorViolationLocationDto location(String className, String memberName, String kind) {
        if (className == null || className.isBlank()) return null;
        try {
            return new AdvisorViolationLocationDto(className, memberName, kind, null, null, null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
