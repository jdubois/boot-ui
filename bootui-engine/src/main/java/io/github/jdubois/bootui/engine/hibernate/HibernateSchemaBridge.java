package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Framework-neutral bridge from the Hibernate metamodel ({@link HibernateEntityModel}) to the small, public,
 * JPA-annotation-free facts the Database Advisor cross-reference rules need.
 *
 * <p>Names are annotation declarations, not effective Hibernate physical names: the declared {@code @Table} catalog/schema/name, the declared
 * {@code @JoinColumn}/{@code @JoinColumns} sets, the declared {@code @Column} name, and — as tri-state values
 * — the declared nullability and length. An entity relying on the default naming strategy is deliberately
 * reported without a table name. Default-valued {@code length}/{@code nullable} members are unknown: reflection
 * cannot distinguish omitted defaults from explicitly written defaults. Only positive nondefault length and
 * {@code nullable=false} are declaration evidence.</p>
 *
 * <p>Attributes whose persisted shape is decided by a converter, an {@code @Enumerated} mapping or an
 * {@code @Lob} are flagged, so the type/length rules can skip exactly the cases where the Java type says
 * nothing reliable about the physical column.</p>
 *
 * <p>This keeps every {@code jakarta.persistence} reflection detail confined to this package (only this class
 * is public outside {@code io.github.jdubois.bootui.engine.hibernate}), while letting the
 * {@code io.github.jdubois.bootui.engine.databaseadvisor} package stay free of Hibernate/JPA reflection
 * concerns and reuse the exact same metamodel the Hibernate Advisor already reads.</p>
 */
public final class HibernateSchemaBridge {

    private static final String TABLE_ANNOTATION = "jakarta.persistence.Table";
    private static final String SECONDARY_TABLE_ANNOTATION = "jakarta.persistence.SecondaryTable";
    private static final String SECONDARY_TABLES_ANNOTATION = "jakarta.persistence.SecondaryTables";
    private static final String NO_CONSTRAINT = "NO_CONSTRAINT";

    private HibernateSchemaBridge() {}

    public static List<MappedEntityFacts> toMappedEntities(List<HibernateEntityModel> entities) {
        Set<Class<?>> unresolvedTypes = new HashSet<>();
        for (HibernateEntityModel entity : entities) {
            if (unresolvedPlacement(entity.javaType())
                    || entities.stream()
                            .anyMatch(other -> other.javaType() != entity.javaType()
                                    && entity.javaType() != null
                                    && other.javaType() != null
                                    && entity.javaType().isAssignableFrom(other.javaType()))) {
                unresolvedTypes.add(entity.javaType());
            }
        }
        List<MappedEntityFacts> mapped = new ArrayList<>();
        for (HibernateEntityModel entity : entities) {
            mapped.add(toMappedEntity(entity, unresolvedTypes));
        }
        return mapped;
    }

    private static MappedEntityFacts toMappedEntity(HibernateEntityModel entity, Set<Class<?>> unresolvedTypes) {
        if (unresolvedTypes.contains(entity.javaType())) {
            return new MappedEntityFacts(entity.name(), null, List.of(), List.of());
        }
        Annotation table = tableAnnotation(entity.javaType());
        String tableName = annotationString(table, "name");
        String schema = annotationString(table, "schema");
        String catalog = annotationString(table, "catalog");
        List<MappedForeignKeyFacts> foreignKeys = new ArrayList<>();
        List<MappedColumnFacts> columns = new ArrayList<>();
        List<MappedUniqueConstraintFacts> uniqueConstraints =
                new ArrayList<>(tableUniqueConstraints(table, entity.name()));
        List<MappedSecondaryTableFacts> secondaryTables = secondaryTables(entity.javaType());
        if (secondaryTables.stream()
                        .map(MappedSecondaryTableFacts::name)
                        .distinct()
                        .count()
                != secondaryTables.size()) {
            return new MappedEntityFacts(entity.name(), null, List.of(), List.of());
        }
        uniqueConstraints.addAll(secondaryTableUniqueConstraints(entity.javaType(), entity.name()));
        List<MappedSequenceGeneratorFacts> sequenceGenerators = new ArrayList<>();
        for (HibernateAttributeModel attribute : entity.attributes()) {
            if (attribute.isTransient() || unsupportedPlacement(attribute)) {
                continue;
            }
            if (isOwningToOne(attribute)) {
                addForeignKey(attribute, foreignKeys, unresolvedTypes);
                continue;
            }
            if (attribute.isAssociation()) {
                continue;
            }
            addColumn(attribute, entity.javaType(), columns, uniqueConstraints);
            addSequenceGenerator(attribute, sequenceGenerators);
        }
        return new MappedEntityFacts(
                entity.name(),
                tableName,
                schema,
                catalog,
                foreignKeys,
                columns,
                uniqueConstraints,
                secondaryTables,
                sequenceGenerators);
    }

    /**
     * Reads a {@code @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "x")} attribute's
     * co-located {@code @SequenceGenerator(name = "x", sequenceName = "...", allocationSize = ...)}, when both
     * annotations are on the same attribute and the generator names match — the common, unambiguous case.
     *
     * <p>A class-level {@code @SequenceGenerator} shared by generator name only (not declared on the {@code
     * @Id} attribute itself), a missing or blank {@code sequenceName}, or a mismatched/absent generator name
     * are all deliberately not resolved: guessing the physical sequence a generator name refers to risks
     * comparing the wrong sequence's {@code INCREMENT BY} entirely.</p>
     */
    private static void addSequenceGenerator(
            HibernateAttributeModel attribute, List<MappedSequenceGeneratorFacts> sequenceGenerators) {
        if (!attribute.hasId()) {
            return;
        }
        Annotation generatedValue = attribute.generatedValueAnnotation();
        if (generatedValue == null || !"SEQUENCE".equals(attribute.annotationValueName(generatedValue, "strategy"))) {
            return;
        }
        Annotation sequenceGenerator = attribute.sequenceGeneratorAnnotation();
        if (sequenceGenerator == null) {
            return;
        }
        String generatorName = attribute.annotationStringValue(generatedValue, "generator");
        String declaredGeneratorName = attribute.annotationStringValue(sequenceGenerator, "name");
        if (generatorName == null || !generatorName.equals(declaredGeneratorName)) {
            return;
        }
        String sequenceName = attribute.annotationStringValue(sequenceGenerator, "sequenceName");
        Integer allocationSize = attribute.annotationIntValue(sequenceGenerator, "allocationSize");
        if (sequenceName == null || sequenceName.isBlank() || allocationSize == null) {
            return;
        }
        sequenceGenerators.add(new MappedSequenceGeneratorFacts(attribute.description(), sequenceName, allocationSize));
    }

    /**
     * Reads every {@code @SecondaryTable} declared on the entity (directly or via a plural
     * {@code @SecondaryTables}), only on the entity itself. An entity that splits its columns across
     * more than one table needs each declaration known
     * so a mapped item explicitly pinned to one (via {@code @Column(table=...)}/{@code @JoinColumn(table=...)})
     * can be checked against the table it actually lives in, instead of the primary table.
     */
    private static List<MappedSecondaryTableFacts> secondaryTables(Class<?> javaType) {
        List<MappedSecondaryTableFacts> tables = new ArrayList<>();
        Class<?> current = javaType;
        if (current != null) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                String typeName = annotation.annotationType().getName();
                if (SECONDARY_TABLE_ANNOTATION.equals(typeName)) {
                    addSecondaryTable(annotation, tables);
                } else if (SECONDARY_TABLES_ANNOTATION.equals(typeName)) {
                    Object value = annotationValue(annotation, "value");
                    if (value instanceof Object[] members) {
                        for (Object member : members) {
                            if (member instanceof Annotation secondaryTable) {
                                addSecondaryTable(secondaryTable, tables);
                            }
                        }
                    }
                }
            }
        }
        return tables;
    }

    private static void addSecondaryTable(Annotation secondaryTable, List<MappedSecondaryTableFacts> tables) {
        String name = annotationString(secondaryTable, "name");
        if (name == null) {
            return;
        }
        tables.add(new MappedSecondaryTableFacts(
                name, annotationString(secondaryTable, "schema"), annotationString(secondaryTable, "catalog")));
    }

    /** {@code @SecondaryTable(uniqueConstraints=...)}, one secondary table at a time. */
    private static List<MappedUniqueConstraintFacts> secondaryTableUniqueConstraints(
            Class<?> javaType, String entityName) {
        List<MappedUniqueConstraintFacts> facts = new ArrayList<>();
        Class<?> current = javaType;
        if (current != null) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                String typeName = annotation.annotationType().getName();
                if (SECONDARY_TABLE_ANNOTATION.equals(typeName)) {
                    facts.addAll(secondaryTableUniqueConstraintsOf(annotation, entityName));
                } else if (SECONDARY_TABLES_ANNOTATION.equals(typeName)) {
                    Object value = annotationValue(annotation, "value");
                    if (value instanceof Object[] members) {
                        for (Object member : members) {
                            if (member instanceof Annotation secondaryTable) {
                                facts.addAll(secondaryTableUniqueConstraintsOf(secondaryTable, entityName));
                            }
                        }
                    }
                }
            }
        }
        return facts;
    }

    private static List<MappedUniqueConstraintFacts> secondaryTableUniqueConstraintsOf(
            Annotation secondaryTable, String entityName) {
        String tableName = annotationString(secondaryTable, "name");
        if (tableName == null) {
            return List.of();
        }
        Object value = annotationValue(secondaryTable, "uniqueConstraints");
        if (!(value instanceof Object[] uniqueConstraints)) {
            return List.of();
        }
        List<MappedUniqueConstraintFacts> facts = new ArrayList<>();
        for (Object uniqueConstraint : uniqueConstraints) {
            Object columnNames = annotationValue(uniqueConstraint, "columnNames");
            if (columnNames instanceof String[] names && names.length > 0) {
                facts.add(new MappedUniqueConstraintFacts(
                        entityName + " @SecondaryTable(" + tableName + ") unique constraint",
                        List.of(names),
                        tableName));
            }
        }
        return facts;
    }

    private static void addColumn(
            HibernateAttributeModel attribute,
            Class<?> entityType,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {
        Annotation column = attribute.columnAnnotation();
        String columnName = column == null ? null : attribute.annotationStringValue(column, "name");
        if (columnName == null || columnName.isBlank()) {
            // No explicit @Column(name): the physical name depends on the naming strategy, which this bridge
            // deliberately does not guess.
            return;
        }
        Boolean nullable = Boolean.FALSE.equals(attribute.annotationBooleanValue(column, "nullable")) ? false : null;
        Integer declaredLength = declaredLength(attribute, column);
        boolean ambiguousType = attribute.hasConvertAnnotation()
                || attribute.annotations().stream().anyMatch(HibernateSchemaBridge::changesColumnRepresentation)
                || hasClassConversion(entityType)
                || annotationString(column, "columnDefinition") != null
                || attribute.isEnumAttribute()
                || attribute.enumeratedAnnotation() != null
                || attribute.isLob();
        String tableName = attribute.annotationStringValue(column, "table");
        columns.add(new MappedColumnFacts(
                attribute.description(),
                columnName,
                nullable,
                attribute.rawType() == null ? null : attribute.rawType().getSimpleName(),
                declaredLength,
                attribute.isLob(),
                ambiguousType,
                attribute.hasId(),
                blankToNull(tableName)));
        if (Boolean.TRUE.equals(attribute.annotationBooleanValue(column, "unique"))) {
            uniqueConstraints.add(new MappedUniqueConstraintFacts(
                    attribute.description(), List.of(columnName), blankToNull(tableName)));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The declared {@code @Column(length=...)}, or {@code null} when the attribute simply did not declare one.
     *
     * <p>JPA defaults {@code length} to 255, so the annotation itself cannot tell "declared 255" from "not
     * declared". Anything equal to the default is therefore treated as not declared: reporting a
     * developer-invisible default against a deliberately narrower column is noise, not a finding.</p>
     */
    private static Integer declaredLength(HibernateAttributeModel attribute, Annotation column) {
        Integer length = attribute.annotationIntValue(column, "length");
        if (length == null || length <= 0 || length == 255) {
            return null;
        }
        return length;
    }

    private static void addForeignKey(
            HibernateAttributeModel attribute, List<MappedForeignKeyFacts> foreignKeys, Set<Class<?>> unresolvedTypes) {
        List<JoinColumnFact> joinColumns = foreignKeyColumns(attribute);
        if (joinColumns.isEmpty()) {
            return;
        }
        String placement = blankToNull(joinColumns.get(0).table());
        if (joinColumns.stream()
                .anyMatch(column -> !java.util.Objects.equals(placement, blankToNull(column.table())))) {
            return;
        }
        List<String> columns = joinColumns.stream().map(JoinColumnFact::name).toList();
        List<String> referencedColumns =
                joinColumns.stream().map(JoinColumnFact::referencedColumnName).toList();
        String tableName = joinColumns.stream()
                .map(JoinColumnFact::table)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        boolean constraintExpected = joinColumns.stream().noneMatch(JoinColumnFact::noConstraint);
        TargetTable target = targetTable(attribute, unresolvedTypes);
        foreignKeys.add(new MappedForeignKeyFacts(
                attribute.description(),
                columns,
                referencedColumns,
                tableName,
                constraintExpected,
                target.name(),
                target.schema(),
                target.catalog()));
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

    /**
     * The association's target entity's table declaration, resolved the same conservative way as the
     * owning entity's own table: only when the target class carries an explicit {@code @Table(name=...)}
     * directly on the target. An explicit {@code targetEntity} takes precedence over the raw attribute type.
     * Entity inheritance and overrides are unresolved rather than assigned guessed placement.
     *
     * <p>Used only to double-check that a physical foreign key candidate actually references the table this
     * association points at — never to guess a target for an entity with no explicit table name, which stays
     * {@code null} here exactly like an unmapped owning entity.</p>
     */
    private static TargetTable targetTable(HibernateAttributeModel attribute, Set<Class<?>> unresolvedTypes) {
        Class<?> rawType = attribute.rawType();
        Annotation association = attribute.manyToOneAnnotation() != null
                ? attribute.manyToOneAnnotation()
                : attribute.oneToOneAnnotation();
        Object targetEntity = annotationValue(association, "targetEntity");
        if (targetEntity instanceof Class<?> target && target != void.class) {
            rawType = target;
        }
        if (rawType == null || unresolvedTypes.contains(rawType) || unresolvedPlacement(rawType)) {
            return TargetTable.UNRESOLVED;
        }
        Annotation table = tableAnnotation(rawType);
        String name = annotationString(table, "name");
        if (name == null) {
            return TargetTable.UNRESOLVED;
        }
        return new TargetTable(name, annotationString(table, "schema"), annotationString(table, "catalog"));
    }

    private record TargetTable(String name, String schema, String catalog) {
        static final TargetTable UNRESOLVED = new TargetTable(null, null, null);
    }

    /** One {@code @JoinColumn}'s resolved facts, before they are split across {@link MappedForeignKeyFacts}. */
    private record JoinColumnFact(String name, String referencedColumnName, String table, boolean noConstraint) {}

    /**
     * The owning side's join columns in declaration order: a single {@code @JoinColumn}, or every
     * {@code @JoinColumn} of a composite {@code @JoinColumns}. A composite association where any member has no
     * explicit name resolves to nothing at all — a partially-known composite key cannot be matched against a
     * physical index without guessing the rest.
     */
    private static List<JoinColumnFact> foreignKeyColumns(HibernateAttributeModel attribute) {
        Annotation joinColumns = attribute.joinColumnsAnnotation();
        if (joinColumns != null) {
            return compositeJoinColumns(attribute, joinColumns);
        }
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn == null) {
            return List.of();
        }
        String name = attribute.annotationStringValue(joinColumn, "name");
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return List.of(new JoinColumnFact(
                name,
                blankToNull(attribute.annotationStringValue(joinColumn, "referencedColumnName")),
                attribute.annotationStringValue(joinColumn, "table"),
                isNoConstraint(annotationValue(joinColumn, "foreignKey"))));
    }

    private static List<JoinColumnFact> compositeJoinColumns(
            HibernateAttributeModel attribute, Annotation joinColumns) {
        // Per the Jakarta Persistence spec, a @ForeignKey declared on the @JoinColumns group governs the whole
        // composite constraint; a provider may still honor one declared on an individual @JoinColumn, so either
        // being NO_CONSTRAINT is enough to mean "no constraint is expected" for the whole association.
        boolean groupNoConstraint = isNoConstraint(annotationValue(joinColumns, "foreignKey"));
        Object value = annotationValue(joinColumns, "value");
        if (!(value instanceof Object[] members) || members.length == 0) {
            return List.of();
        }
        List<JoinColumnFact> columns = new ArrayList<>();
        for (Object member : members) {
            if (!(member instanceof Annotation joinColumn)) {
                return List.of();
            }
            String name = attribute.annotationStringValue(joinColumn, "name");
            if (name == null || name.isBlank()) {
                return List.of();
            }
            columns.add(new JoinColumnFact(
                    name,
                    blankToNull(attribute.annotationStringValue(joinColumn, "referencedColumnName")),
                    attribute.annotationStringValue(joinColumn, "table"),
                    groupNoConstraint || isNoConstraint(annotationValue(joinColumn, "foreignKey"))));
        }
        return List.copyOf(columns);
    }

    /** {@code true} when a {@code @ForeignKey} annotation explicitly declares {@code ConstraintMode.NO_CONSTRAINT}. */
    private static boolean isNoConstraint(Object foreignKeyAnnotation) {
        if (foreignKeyAnnotation == null) {
            return false;
        }
        Object mode = annotationValue(foreignKeyAnnotation, "value");
        return mode instanceof Enum<?> enumValue && NO_CONSTRAINT.equals(enumValue.name());
    }

    /**
     * Reads {@code @Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))} multi-column unique
     * constraints declared directly on the entity.
     */
    private static List<MappedUniqueConstraintFacts> tableUniqueConstraints(Annotation table, String entityName) {
        if (table == null) {
            return List.of();
        }
        Object value = annotationValue(table, "uniqueConstraints");
        if (!(value instanceof Object[] uniqueConstraints)) {
            return List.of();
        }
        List<MappedUniqueConstraintFacts> facts = new ArrayList<>();
        for (Object uniqueConstraint : uniqueConstraints) {
            Object columnNames = annotationValue(uniqueConstraint, "columnNames");
            if (columnNames instanceof String[] names && names.length > 0) {
                facts.add(new MappedUniqueConstraintFacts(
                        entityName + " @Table unique constraint", List.of(names), null));
            }
        }
        return facts;
    }

    private static Annotation tableAnnotation(Class<?> javaType) {
        if (javaType != null) {
            for (Annotation annotation : javaType.getDeclaredAnnotations()) {
                if (TABLE_ANNOTATION.equals(annotation.annotationType().getName())) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static boolean unresolvedPlacement(Class<?> javaType) {
        if (javaType == null) {
            return true;
        }
        for (Class<?> current = javaType;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                if ((current != javaType && name.equals("jakarta.persistence.Entity"))
                        || name.equals("jakarta.persistence.Inheritance")
                        || name.equals("jakarta.persistence.AttributeOverride")
                        || name.equals("jakarta.persistence.AttributeOverrides")
                        || name.equals("jakarta.persistence.AssociationOverride")
                        || name.equals("jakarta.persistence.AssociationOverrides")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean unsupportedPlacement(HibernateAttributeModel attribute) {
        return attribute.isElementCollection()
                || attribute.annotations().stream().anyMatch(annotation -> {
                    String name = annotation.annotationType().getName();
                    return name.equals("jakarta.persistence.JoinTable")
                            || name.equals("jakarta.persistence.Embedded")
                            || name.equals("jakarta.persistence.EmbeddedId")
                            || name.equals("jakarta.persistence.AttributeOverride")
                            || name.equals("jakarta.persistence.AttributeOverrides")
                            || name.equals("jakarta.persistence.AssociationOverride")
                            || name.equals("jakarta.persistence.AssociationOverrides")
                            || name.equals("org.hibernate.annotations.Formula")
                            || name.equals("org.hibernate.annotations.JoinFormula")
                            || name.equals("org.hibernate.annotations.JoinColumnOrFormula")
                            || name.equals("org.hibernate.annotations.JoinColumnsOrFormulas");
                });
    }

    private static boolean changesColumnRepresentation(Annotation annotation) {
        String name = annotation.annotationType().getName();
        return name.equals("jakarta.persistence.Convert")
                || name.equals("jakarta.persistence.Converts")
                || name.equals("org.hibernate.annotations.Type")
                || name.equals("org.hibernate.annotations.JdbcType")
                || name.equals("org.hibernate.annotations.JdbcTypeCode")
                || name.equals("org.hibernate.annotations.JavaType")
                || name.equals("org.hibernate.annotations.ColumnTransformer")
                || name.equals("org.hibernate.annotations.ColumnTransformers");
    }

    private static boolean hasClassConversion(Class<?> entityType) {
        for (Class<?> current = entityType;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (changesColumnRepresentation(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String annotationString(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static Object annotationValue(Object annotation, String attributeName) {
        if (annotation == null) {
            return null;
        }
        try {
            Class<?> declaringType =
                    annotation instanceof Annotation typed ? typed.annotationType() : annotation.getClass();
            return declaringType.getMethod(attributeName).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * @param entityName the entity's fully-qualified Java type name
     * @param explicitTableName the explicit {@code @Table(name=...)} value, or {@code null} when the entity
     *     relies on the default naming strategy (deliberately not guessed)
     * @param explicitSchema the explicit {@code @Table(schema=...)} value, or {@code null}
     * @param explicitCatalog the explicit {@code @Table(catalog=...)} value, or {@code null}
     * @param foreignKeys owning {@code @ManyToOne}/{@code @OneToOne} associations with fully-resolved join
     *     column names, in declaration order
     * @param columns basic mapped attributes with an explicit {@code @Column(name=...)}
     * @param uniqueConstraints single-column {@code @Column(unique=true)} attributes and multi-column
     *     {@code @Table(uniqueConstraints=...)}/{@code @SecondaryTable(uniqueConstraints=...)} constraints
     * @param secondaryTables every {@code @SecondaryTable} declared on the entity, explicitly named
     * @param sequenceGenerators {@code @GeneratedValue(strategy=SEQUENCE)} attributes with a co-located,
     *     matching {@code @SequenceGenerator} that explicitly names a physical {@code sequenceName}
     */
    public record MappedEntityFacts(
            String entityName,
            String explicitTableName,
            String explicitSchema,
            String explicitCatalog,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints,
            List<MappedSecondaryTableFacts> secondaryTables,
            List<MappedSequenceGeneratorFacts> sequenceGenerators) {

        public MappedEntityFacts {
            foreignKeys = List.copyOf(foreignKeys);
            columns = List.copyOf(columns);
            uniqueConstraints = List.copyOf(uniqueConstraints);
            secondaryTables = List.copyOf(secondaryTables);
            sequenceGenerators = List.copyOf(sequenceGenerators);
        }

        /** Convenience constructor for callers with no explicit schema/catalog and no unique constraints. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns) {
            this(entityName, explicitTableName, null, null, foreignKeys, columns, List.of(), List.of(), List.of());
        }

        /** Convenience constructor for callers with no explicit schema/catalog. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns,
                List<MappedUniqueConstraintFacts> uniqueConstraints) {
            this(
                    entityName,
                    explicitTableName,
                    null,
                    null,
                    foreignKeys,
                    columns,
                    uniqueConstraints,
                    List.of(),
                    List.of());
        }

        /** Convenience constructor for callers with no {@code @SecondaryTable}s or sequence generators. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                String explicitSchema,
                String explicitCatalog,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns,
                List<MappedUniqueConstraintFacts> uniqueConstraints) {
            this(
                    entityName,
                    explicitTableName,
                    explicitSchema,
                    explicitCatalog,
                    foreignKeys,
                    columns,
                    uniqueConstraints,
                    List.of(),
                    List.of());
        }

        /** Convenience constructor for callers with no sequence generators. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                String explicitSchema,
                String explicitCatalog,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns,
                List<MappedUniqueConstraintFacts> uniqueConstraints,
                List<MappedSecondaryTableFacts> secondaryTables) {
            this(
                    entityName,
                    explicitTableName,
                    explicitSchema,
                    explicitCatalog,
                    foreignKeys,
                    columns,
                    uniqueConstraints,
                    secondaryTables,
                    List.of());
        }

        /** {@code schema.table} when a schema was declared, otherwise the bare declared table name. */
        public String qualifiedTableName() {
            if (explicitSchema == null || explicitSchema.isBlank()) {
                return explicitTableName;
            }
            return explicitSchema + "." + explicitTableName;
        }
    }

    /**
     * One {@code @SecondaryTable} an entity's columns can be split across.
     *
     * @param name the explicit {@code @SecondaryTable(name=...)} value
     * @param schema the explicit {@code @SecondaryTable(schema=...)} value, or {@code null}
     * @param catalog the explicit {@code @SecondaryTable(catalog=...)} value, or {@code null}
     */
    public record MappedSecondaryTableFacts(String name, String schema, String catalog) {

        /** {@code schema.table} when a schema was declared, otherwise the bare table name. */
        public String qualifiedName() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }
    }

    /**
     * A {@code @GeneratedValue(strategy = GenerationType.SEQUENCE)} attribute's co-located, explicitly-named
     * {@code @SequenceGenerator}: its sequence-name and allocation-size declarations, not effective optimizer evidence.
     *
     * @param attributeDescription a human-readable "Entity.attribute" description for finding details
     * @param sequenceName the explicit {@code @SequenceGenerator(sequenceName = ...)} value
     * @param allocationSize the declared {@code @SequenceGenerator(allocationSize = ...)}
     */
    public record MappedSequenceGeneratorFacts(String attributeDescription, String sequenceName, int allocationSize) {}

    /**
     * @param attributeDescription a human-readable "Entity.attribute" description for finding details
     * @param columns the referencing (child) join column names, in declaration order
     * @param referencedColumns the explicit {@code @JoinColumn(referencedColumnName=...)} for each entry in
     *     {@code columns}, positionally aligned; {@code null} for a join column that left it unspecified (the
     *     common case, which JPA defaults to the referenced entity's own primary key column)
     * @param tableName the explicit {@code @JoinColumn(table=...)}/{@code @JoinColumns(... )} secondary-table
     *     name the join column(s) belong to, or {@code null} for the entity's primary table
     * @param constraintExpected {@code false} when the mapping explicitly declares
     *     {@code @ForeignKey(ConstraintMode.NO_CONSTRAINT)}, meaning the absence of a physical foreign key
     *     constraint is intentional and must not be reported as a gap
     * @param targetTableName the association's target entity's explicit {@code @Table(name=...)}, or
     *     {@code null} when the target relies on the default naming strategy (not guessed)
     * @param targetSchema the target entity's explicit {@code @Table(schema=...)}, or {@code null}
     * @param targetCatalog the target entity's explicit {@code @Table(catalog=...)}, or {@code null}
     */
    public record MappedForeignKeyFacts(
            String attributeDescription,
            List<String> columns,
            List<String> referencedColumns,
            String tableName,
            boolean constraintExpected,
            String targetTableName,
            String targetSchema,
            String targetCatalog) {

        public MappedForeignKeyFacts {
            columns = List.copyOf(columns);
            // referencedColumns legitimately contains null entries (an unspecified referencedColumnName), so
            // it cannot use List.copyOf, which rejects null elements outright.
            referencedColumns = Collections.unmodifiableList(new ArrayList<>(referencedColumns));
        }

        /**
         * Convenience constructor for callers with no referenced-column/table/constraint-mode/target-table
         * information: every join column's referenced column is unknown, a constraint is expected, and the
         * target table is unresolved.
         */
        public MappedForeignKeyFacts(String attributeDescription, List<String> columns) {
            this(
                    attributeDescription,
                    columns,
                    columns.stream().map(column -> (String) null).toList(),
                    null,
                    true,
                    null,
                    null,
                    null);
        }

        /** {@code true} when the target entity declares a table name; physical naming may still transform it. */
        public boolean targetTableResolved() {
            return targetTableName != null && !targetTableName.isBlank();
        }
    }

    /**
     * @param nullable {@code false} for nondefault {@code @Column(nullable=false)}, otherwise {@code null}
     * @param javaTypeSimpleName the attribute's raw Java type simple name (e.g. {@code String})
     * @param declaredLength the declared {@code @Column(length=...)}, or {@code null} when not declared or
     *     left at the JPA default
     * @param lob whether the attribute is an {@code @Lob}
     * @param ambiguousType whether a converter/{@code @Enumerated}/{@code @Lob} decides the persisted shape,
     *     so the Java type says nothing reliable about the physical column
     * @param identifier whether the attribute is the entity's {@code @Id}
     * @param tableName the explicit {@code @Column(table=...)} secondary-table name, or {@code null} for the
     *     entity's primary table
     */
    public record MappedColumnFacts(
            String attributeDescription,
            String columnName,
            Boolean nullable,
            String javaTypeSimpleName,
            Integer declaredLength,
            boolean lob,
            boolean ambiguousType,
            boolean identifier,
            String tableName) {

        /** Convenience constructor for callers with no length/LOB/converter/table information. */
        public MappedColumnFacts(
                String attributeDescription, String columnName, Boolean nullable, String javaTypeSimpleName) {
            this(attributeDescription, columnName, nullable, javaTypeSimpleName, null, false, false, false, null);
        }

        /** Convenience constructor for callers with an explicit declared length only. */
        public MappedColumnFacts(
                String attributeDescription,
                String columnName,
                Boolean nullable,
                String javaTypeSimpleName,
                Integer declaredLength) {
            this(
                    attributeDescription,
                    columnName,
                    nullable,
                    javaTypeSimpleName,
                    declaredLength,
                    false,
                    false,
                    false,
                    null);
        }
    }

    /**
     * A mapped unique constraint: a single-column {@code @Column(unique=true)} attribute, a multi-column
     * {@code @Table(uniqueConstraints=...)} constraint, or a {@code @SecondaryTable(uniqueConstraints=...)}
     * constraint.
     *
     * @param description a human-readable description for finding details
     * @param tableName the secondary table this constraint belongs to, or {@code null} for the entity's
     *     primary table
     */
    public record MappedUniqueConstraintFacts(String description, List<String> columns, String tableName) {

        public MappedUniqueConstraintFacts {
            columns = List.copyOf(columns);
        }

        /** Convenience constructor for a primary-table constraint. */
        public MappedUniqueConstraintFacts(String description, List<String> columns) {
            this(description, columns, null);
        }
    }
}
