package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-neutral bridge from the Hibernate metamodel ({@link HibernateEntityModel}) to the small,
 * public, JPA-annotation-free facts the Database Advisor cross-reference rules need: the entity's
 * explicitly-declared {@code @Table} name (if any — an entity relying on the default naming strategy is
 * deliberately reported without a table name rather than guessing the physical name, keeping the
 * cross-reference low-false-positive), its owning foreign-key columns, and its basic mapped columns.
 *
 * <p>This keeps every {@code jakarta.persistence} reflection detail confined to this package (only this
 * class is public outside {@code io.github.jdubois.bootui.engine.hibernate}), while letting the
 * {@code io.github.jdubois.bootui.engine.databaseadvisor} package stay free of Hibernate/JPA
 * reflection concerns and reuse the exact same metamodel the Hibernate Advisor already reads.</p>
 */
public final class HibernateSchemaBridge {

    private HibernateSchemaBridge() {}

    public static List<MappedEntityFacts> toMappedEntities(List<HibernateEntityModel> entities) {
        List<MappedEntityFacts> mapped = new ArrayList<>();
        for (HibernateEntityModel entity : entities) {
            String tableName = explicitTableName(entity.javaType());
            List<MappedForeignKeyFacts> foreignKeys = new ArrayList<>();
            List<MappedColumnFacts> columns = new ArrayList<>();
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.isTransient()) {
                    continue;
                }
                if (isOwningToOne(attribute)) {
                    List<String> fkColumns = foreignKeyColumns(attribute);
                    if (!fkColumns.isEmpty()) {
                        foreignKeys.add(new MappedForeignKeyFacts(attribute.description(), fkColumns));
                    }
                    continue;
                }
                if (attribute.isAssociation() || attribute.hasId()) {
                    continue;
                }
                Annotation column = attribute.columnAnnotation();
                String columnName = column == null ? null : attribute.annotationStringValue(column, "name");
                if (columnName == null || columnName.isBlank()) {
                    continue;
                }
                Boolean columnNullable = column == null ? null : attribute.annotationBooleanValue(column, "nullable");
                boolean nullable = columnNullable == null ? attribute.isOptionalAttribute() : columnNullable;
                columns.add(new MappedColumnFacts(
                        attribute.description(), columnName, nullable, attribute.rawType().getSimpleName()));
            }
            mapped.add(new MappedEntityFacts(entity.name(), tableName, foreignKeys, columns));
        }
        return mapped;
    }

    private static boolean isOwningToOne(HibernateAttributeModel attribute) {
        if (attribute.manyToOneAnnotation() != null) {
            return true;
        }
        Annotation oneToOne = attribute.oneToOneAnnotation();
        if (oneToOne == null) {
            return false;
        }
        String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
        return mappedBy == null || mappedBy.isBlank();
    }

    private static List<String> foreignKeyColumns(HibernateAttributeModel attribute) {
        List<String> columns = new ArrayList<>();
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn != null) {
            String name = attribute.annotationStringValue(joinColumn, "name");
            if (name != null && !name.isBlank()) {
                columns.add(name);
            }
        }
        return columns;
    }

    private static String explicitTableName(Class<?> javaType) {
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (!"jakarta.persistence.Table".equals(annotation.annotationType().getName())) {
                    continue;
                }
                try {
                    String name = (String)
                            annotation.annotationType().getMethod("name").invoke(annotation);
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                } catch (ReflectiveOperationException ex) {
                    return null;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * @param entityName the entity's fully-qualified Java type name
     * @param explicitTableName the explicit {@code @Table(name=...)} value, or {@code null} when the entity
     *     relies on the default naming strategy (deliberately not guessed)
     * @param foreignKeys owning {@code @ManyToOne}/{@code @OneToOne} associations with a resolvable
     *     {@code @JoinColumn} name
     * @param columns basic mapped attributes with an explicit {@code @Column(name=...)}
     */
    public record MappedEntityFacts(
            String entityName,
            String explicitTableName,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns) {

        public MappedEntityFacts {
            foreignKeys = List.copyOf(foreignKeys);
            columns = List.copyOf(columns);
        }
    }

    /** @param attributeDescription a human-readable "Entity.attribute" description for finding details */
    public record MappedForeignKeyFacts(String attributeDescription, List<String> columns) {

        public MappedForeignKeyFacts {
            columns = List.copyOf(columns);
        }
    }

    /** @param javaTypeSimpleName the attribute's raw Java type simple name (e.g. {@code String}, {@code Integer}) */
    public record MappedColumnFacts(
            String attributeDescription, String columnName, boolean nullable, String javaTypeSimpleName) {}
}
