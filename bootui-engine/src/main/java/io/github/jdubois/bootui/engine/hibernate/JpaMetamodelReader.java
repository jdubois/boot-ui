package io.github.jdubois.bootui.engine.hibernate;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

/** The sole bootui-engine class that reads the {@code jakarta.persistence} metamodel. */
public final class JpaMetamodelReader {

    private JpaMetamodelReader() {}

    public static EntityDiscovery readEntities(List<EntityManagerFactory> factories) {
        if (factories.isEmpty()) {
            return EntityDiscovery.empty("No EntityManagerFactory beans are available.");
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
        for (Attribute<?, ?> attribute : entityType.getAttributes()) {
            Member member = attribute.getJavaMember();
            if (member != null) {
                attributes.add(toAttributeModel(attribute, member));
            }
        }
        String name = javaType == null ? entityType.getName() : javaType.getName();
        return new HibernateEntityModel(name, javaType, attributes);
    }

    static HibernateAttributeModel toAttributeModel(Attribute<?, ?> attribute, Member member) {
        String persistentAttributeType = attribute.getPersistentAttributeType() == null
                ? null
                : attribute.getPersistentAttributeType().name();
        return HibernateAttributeModel.fromMember(attribute.getName(), member, persistentAttributeType);
    }
}
