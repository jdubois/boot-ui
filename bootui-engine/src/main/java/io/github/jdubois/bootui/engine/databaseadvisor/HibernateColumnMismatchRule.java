package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cross-references mapped {@code @Column(name=...)} attributes against the physical column: a coarse
 * type-family mismatch (e.g. a {@code String} attribute mapped to a numeric column) or a nullability
 * mismatch where the database is stricter than the mapping (a {@code NOT NULL} column mapped to a
 * nullable attribute, or vice versa) usually surfaces at runtime as a surprising constraint violation or
 * class-cast/conversion failure rather than at compile time.
 */
final class HibernateColumnMismatchRule extends AbstractDatabaseAdvisorRule {

    private static final Set<String> STRING_TYPES = Set.of("char", "clob", "text");
    private static final Set<String> NUMERIC_TYPES =
            Set.of("int", "numeric", "decimal", "float", "double", "real", "serial", "money");
    private static final Set<String> BOOLEAN_TYPES = Set.of("bool");
    private static final Set<String> DATE_TIME_TYPES = Set.of("date", "time", "timestamp");
    private static final Set<String> BINARY_TYPES = Set.of("blob", "binary", "bytea");

    private static final Set<String> STRING_JAVA_TYPES = Set.of("String", "Character", "char");
    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of(
            "byte",
            "short",
            "int",
            "long",
            "float",
            "double",
            "Byte",
            "Short",
            "Integer",
            "Long",
            "Float",
            "Double",
            "BigDecimal",
            "BigInteger");
    private static final Set<String> BOOLEAN_JAVA_TYPES = Set.of("boolean", "Boolean");
    private static final Set<String> DATE_TIME_JAVA_TYPES = Set.of(
            "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime",
            "Timestamp", "Time");

    HibernateColumnMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-003",
                "Mapped column type/nullability mismatch",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references mapped @Column(name=...) attributes against the physical column's reported JDBC "
                        + "type family and nullability.",
                "Align the entity mapping with the physical column: a coarse type-family mismatch (text vs. "
                        + "numeric vs. date/time) usually fails at read/conversion time, and a NOT NULL column "
                        + "mapped as nullable can throw a constraint violation only under specific code paths.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        if (context.availableSchemas().isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            if (entity.explicitTableName() == null) {
                continue;
            }
            TableModel table = findTable(context, entity.explicitTableName());
            if (table == null) {
                continue;
            }
            for (MappedColumnFacts column : entity.columns()) {
                ColumnModel physical = table.column(column.columnName());
                if (physical == null) {
                    continue;
                }
                checkTypeFamily(entity, column, physical, details);
                checkNullability(entity, column, physical, details);
            }
        }
        return violation(details);
    }

    private void checkTypeFamily(
            MappedEntityFacts entity, MappedColumnFacts column, ColumnModel physical, List<String> details) {
        TypeFamily javaFamily = TypeFamily.ofJava(column.javaTypeSimpleName());
        TypeFamily columnFamily = TypeFamily.ofJdbc(physical.typeName());
        if (javaFamily != TypeFamily.OTHER && columnFamily != TypeFamily.OTHER && javaFamily != columnFamily) {
            details.add(column.attributeDescription() + " (" + column.javaTypeSimpleName() + ") maps column "
                    + entity.explicitTableName() + "." + column.columnName() + " (" + physical.typeName()
                    + "), a type-family mismatch.");
        }
    }

    private void checkNullability(
            MappedEntityFacts entity, MappedColumnFacts column, ColumnModel physical, List<String> details) {
        if (!physical.nullable() && column.nullable()) {
            details.add(column.attributeDescription() + " maps column " + entity.explicitTableName() + "."
                    + column.columnName()
                    + ", which is NOT NULL in the database but is mapped as nullable in the entity.");
        } else if (physical.nullable() && !column.nullable()) {
            details.add(column.attributeDescription() + " maps column " + entity.explicitTableName() + "."
                    + column.columnName()
                    + ", which allows NULL in the database but is mapped as non-nullable in the entity.");
        }
    }

    private TableModel findTable(DatabaseAdvisorContext context, String tableName) {
        for (SchemaSnapshot schema : context.availableSchemas()) {
            TableModel table = schema.table(tableName);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    private enum TypeFamily {
        STRING,
        NUMERIC,
        BOOLEAN,
        DATE_TIME,
        BINARY,
        OTHER;

        static TypeFamily ofJava(String javaTypeSimpleName) {
            if (javaTypeSimpleName == null) {
                return OTHER;
            }
            if (STRING_JAVA_TYPES.contains(javaTypeSimpleName)) {
                return STRING;
            }
            if (NUMERIC_JAVA_TYPES.contains(javaTypeSimpleName)) {
                return NUMERIC;
            }
            if (BOOLEAN_JAVA_TYPES.contains(javaTypeSimpleName)) {
                return BOOLEAN;
            }
            if (DATE_TIME_JAVA_TYPES.contains(javaTypeSimpleName)) {
                return DATE_TIME;
            }
            return OTHER;
        }

        static TypeFamily ofJdbc(String jdbcTypeName) {
            if (jdbcTypeName == null) {
                return OTHER;
            }
            String normalized = jdbcTypeName.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, BOOLEAN_TYPES)) {
                return BOOLEAN;
            }
            if (containsAny(normalized, DATE_TIME_TYPES)) {
                return DATE_TIME;
            }
            if (containsAny(normalized, BINARY_TYPES)) {
                return BINARY;
            }
            if (containsAny(normalized, STRING_TYPES)) {
                return STRING;
            }
            if (containsAny(normalized, NUMERIC_TYPES)) {
                return NUMERIC;
            }
            return OTHER;
        }

        private static boolean containsAny(String value, Set<String> needles) {
            return needles.stream().anyMatch(value::contains);
        }
    }
}
