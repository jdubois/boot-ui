package io.github.jdubois.bootui.engine.hibernate;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The sole bootui-engine class that reads the {@code jakarta.persistence} metamodel. */
public final class JpaMetamodelReader {

    private JpaMetamodelReader() {}

    public static EntityDiscovery readEntities(List<EntityManagerFactory> factories) {
        if (factories.isEmpty()) {
            return EntityDiscovery.absent("No EntityManagerFactory beans are available.");
        }
        List<HibernateEntityModel> entities = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (EntityManagerFactory factory : factories) {
            try {
                Metamodel metamodel = factory.getMetamodel();
                for (EntityType<?> entityType : metamodel.getEntities()) {
                    try {
                        if (isFrameworkProvided(entityType.getJavaType())) {
                            continue;
                        }
                        entities.add(toEntityModel(entityType));
                    } catch (RuntimeException | LinkageError ex) {
                        errors.add("Entity " + entityType.getName() + ": "
                                + (ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()));
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                errors.add(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage());
            }
        }
        return new EntityDiscovery(entities, List.of(), errors);
    }

    /**
     * Entities Hibernate Envers adds itself, such as {@code DefaultRevisionEntity}, appear in the JPA metamodel but
     * cannot be edited by the application, so no finding about them is actionable. A revision entity the
     * application declares in its own package is not excluded. Envers' generated {@code *_AUD} entities are
     * map-based and never reach the JPA metamodel at all.
     */
    private static final String ENVERS_PACKAGE_PREFIX = "org.hibernate.envers.";

    static boolean isFrameworkProvided(Class<?> javaType) {
        return javaType != null && javaType.getName().startsWith(ENVERS_PACKAGE_PREFIX);
    }

    static HibernateEntityModel toEntityModel(EntityType<?> entityType) {
        Class<?> javaType = entityType.getJavaType();
        List<HibernateAttributeModel> attributes = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        if (javaType != null) {
            visited.add(javaType);
        }
        collectAttributes(
                entityType.getAttributes(),
                "",
                HibernateEntityModel.classLevelOverrides(javaType),
                visited,
                0,
                attributes);
        String name = javaType == null ? entityType.getName() : javaType.getName();
        return new HibernateEntityModel(name, javaType, attributes);
    }

    private static void collectAttributes(
            Set<? extends Attribute<?, ?>> sourceAttributes,
            String prefix,
            List<HibernateEntityModel.ScopedOverride> inheritedOverrides,
            Set<Class<?>> visited,
            int depth,
            List<HibernateAttributeModel> target) {
        if (sourceAttributes == null) {
            return;
        }
        for (Attribute<?, ?> attribute : sourceAttributes) {
            Member member = attribute.getJavaMember();
            if (member == null) {
                continue;
            }
            String attributeName = attribute.getName();
            String qualifiedName = prefix.isEmpty() ? attributeName : prefix + "." + attributeName;
            HibernateAttributeModel model =
                    toAttributeModel(attribute, member, qualifiedName, attributeName, inheritedOverrides);
            target.add(model);
            if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.EMBEDDED
                    && !isEmbeddedId(attribute, model)
                    && depth < HibernateEntityModel.MAX_EMBEDDED_DEPTH) {
                if (attribute instanceof SingularAttribute<?, ?> singular
                        && singular.getType() instanceof ManagedType<?> managedType) {
                    Class<?> embeddableType = managedType.getJavaType();
                    List<HibernateEntityModel.ScopedOverride> nextOverrides = HibernateEntityModel.childOverrides(
                            inheritedOverrides, model.annotations(), attributeName, embeddableType);
                    if (embeddableType == null || visited.add(embeddableType)) {
                        try {
                            collectAttributes(
                                    managedType.getAttributes(),
                                    qualifiedName,
                                    nextOverrides,
                                    visited,
                                    depth + 1,
                                    target);
                        } finally {
                            if (embeddableType != null) {
                                visited.remove(embeddableType);
                            }
                        }
                    }
                } else if (model.rawType() != null && visited.add(model.rawType())) {
                    try {
                        List<HibernateEntityModel.ScopedOverride> nextOverrides = HibernateEntityModel.childOverrides(
                                inheritedOverrides, model.annotations(), attributeName, model.rawType());
                        HibernateEntityModel.collectClassAttributes(
                                model.rawType(), qualifiedName, nextOverrides, visited, depth + 1, target);
                    } finally {
                        visited.remove(model.rawType());
                    }
                }
            }
        }
    }

    private static boolean isEmbeddedId(Attribute<?, ?> attribute, HibernateAttributeModel model) {
        return (attribute instanceof SingularAttribute<?, ?> singular && singular.isId())
                || model.annotation("jakarta.persistence.EmbeddedId") != null;
    }

    static HibernateAttributeModel toAttributeModel(Attribute<?, ?> attribute, Member member) {
        return toAttributeModel(attribute, member, attribute.getName(), attribute.getName(), List.of());
    }

    private static HibernateAttributeModel toAttributeModel(
            Attribute<?, ?> attribute,
            Member member,
            String qualifiedName,
            String simpleName,
            List<HibernateEntityModel.ScopedOverride> inheritedOverrides) {
        String persistentAttributeType = attribute.getPersistentAttributeType() == null
                ? null
                : attribute.getPersistentAttributeType().name();
        return HibernateAttributeModel.fromMember(
                qualifiedName,
                member,
                persistentAttributeType,
                HibernateEntityModel.matchingOverrides(inheritedOverrides, simpleName));
    }
}
