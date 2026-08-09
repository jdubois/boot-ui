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

    /** The JPA-specified default {@code @Column(length=...)} when the attribute is not explicit. */
    private static final int DEFAULT_COLUMN_LENGTH = 255;

    public static List<MappedEntityFacts> toMappedEntities(List<HibernateEntityModel> entities) {
        List<MappedEntityFacts> mapped = new ArrayList<>();
        for (HibernateEntityModel entity : entities) {
            String tableName = explicitTableName(entity.javaType());
            List<MappedForeignKeyFacts> foreignKeys = new ArrayList<>();
            List<MappedColumnFacts> columns = new ArrayList<>();
            List<MappedUniqueConstraintFacts> uniqueConstraints =
                    new ArrayList<>(tableUniqueConstraints(entity.javaType(), entity.name()));
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
                Integer columnLength = attribute.annotationIntValue(column, "length");
                columns.add(new MappedColumnFacts(
                        attribute.description(),
                        columnName,
                        nullable,
                        attribute.rawType().getSimpleName(),
                        columnLength == null ? DEFAULT_COLUMN_LENGTH : columnLength));
                Boolean columnUnique = attribute.annotationBooleanValue(column, "unique");
                if (Boolean.TRUE.equals(columnUnique)) {
                    uniqueConstraints.add(
                            new MappedUniqueConstraintFacts(attribute.description(), List.of(columnName)));
                }
            }
            mapped.add(new MappedEntityFacts(entity.name(), tableName, foreignKeys, columns, uniqueConstraints));
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

    /**
     * Reads {@code @Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))} multi-column unique
     * constraints declared on the entity or one of its mapped superclasses.
     */
    private static List<MappedUniqueConstraintFacts> tableUniqueConstraints(Class<?> javaType, String entityName) {
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (!"jakarta.persistence.Table"
                        .equals(annotation.annotationType().getName())) {
                    continue;
                }
                return uniqueConstraintsOf(annotation, entityName);
            }
            current = current.getSuperclass();
        }
        return List.of();
    }

    private static List<MappedUniqueConstraintFacts> uniqueConstraintsOf(
            Annotation tableAnnotation, String entityName) {
        try {
            Object[] uniqueConstraints = (Object[]) tableAnnotation
                    .annotationType()
                    .getMethod("uniqueConstraints")
                    .invoke(tableAnnotation);
            List<MappedUniqueConstraintFacts> facts = new ArrayList<>();
            for (Object uniqueConstraint : uniqueConstraints) {
                String[] columnNames = (String[])
                        uniqueConstraint.getClass().getMethod("columnNames").invoke(uniqueConstraint);
                if (columnNames.length > 0) {
                    facts.add(new MappedUniqueConstraintFacts(
                            entityName + " @Table unique constraint", List.of(columnNames)));
                }
            }
            return facts;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return List.of();
        }
    }

    private static String explicitTableName(Class<?> javaType) {
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (!"jakarta.persistence.Table"
                        .equals(annotation.annotationType().getName())) {
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
     * @param uniqueConstraints single-column {@code @Column(unique=true)} attributes and multi-column
     *     {@code @Table(uniqueConstraints=...)} constraints
     */
    public record MappedEntityFacts(
            String entityName,
            String explicitTableName,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {

        public MappedEntityFacts {
            foreignKeys = List.copyOf(foreignKeys);
            columns = List.copyOf(columns);
            uniqueConstraints = List.copyOf(uniqueConstraints);
        }

        /** Convenience constructor for callers with no mapped unique constraint facts. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns) {
            this(entityName, explicitTableName, foreignKeys, columns, List.of());
        }
    }

    /** @param attributeDescription a human-readable "Entity.attribute" description for finding details */
    public record MappedForeignKeyFacts(String attributeDescription, List<String> columns) {

        public MappedForeignKeyFacts {
            columns = List.copyOf(columns);
        }
    }

    /**
     * @param javaTypeSimpleName the attribute's raw Java type simple name (e.g. {@code String}, {@code Integer})
     * @param columnLength the declared {@code @Column(length=...)}, or the JPA default of 255 when not
     *     explicit; only meaningful for string/char-family columns
     */
    public record MappedColumnFacts(
            String attributeDescription,
            String columnName,
            boolean nullable,
            String javaTypeSimpleName,
            int columnLength) {

        /** Convenience constructor for callers with no explicit declared column length. */
        public MappedColumnFacts(
                String attributeDescription, String columnName, boolean nullable, String javaTypeSimpleName) {
            this(attributeDescription, columnName, nullable, javaTypeSimpleName, 255);
        }
    }

    /**
     * A mapped unique constraint, either a single-column {@code @Column(unique=true)} attribute or a
     * multi-column {@code @Table(uniqueConstraints=...)} constraint.
     *
     * @param description a human-readable description for finding details
     */
    public record MappedUniqueConstraintFacts(String description, List<String> columns) {

        public MappedUniqueConstraintFacts {
            columns = List.copyOf(columns);
        }
    }
}
