package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.sql.Types;
import java.util.List;

/** Compares nondefault DDL lengths with positively bounded character-column metadata, not runtime validation. */
final class HibernateColumnLengthMismatchRule extends AbstractHibernateCrossReferenceRule {

    HibernateColumnLengthMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-004",
                "Declared column length exceeds observed column size",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares positive nondefault @Column(length=...) declarations with bounded character-column sizes. "
                        + "Explicit @Enumerated(STRING) enums are compared; LOBs, known converters, native column "
                        + "definitions, other enum mappings and unknown sizes are not.",
                "Review the DDL declaration and observed column size after confirming the effective physical mapping. "
                        + "@Column(length) is schema-generation metadata, not a runtime input validator; this "
                        + "comparison alone does not establish truncation or accepted application input.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.columns().stream().anyMatch(this::applicable);
    }

    private boolean applicable(MappedColumnFacts column) {
        return column.declaredLength() != null
                && column.declaredLength() > 0
                && column.declaredLength() != 255
                && !column.lob();
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return true;
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        int eligible = 0;
        for (MappedColumnFacts column : entity.columns()) {
            if (!applicable(column)) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, column.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            if (checkColumn(context, resolution.schema(), resolution.table(), column, details)) {
                eligible++;
            }
        }
        return eligible;
    }

    private boolean checkColumn(
            DatabaseAdvisorContext context,
            SchemaSnapshot schema,
            TableModel table,
            MappedColumnFacts column,
            List<String> details) {
        Integer declaredLength = column.declaredLength();
        ColumnModel physical = schema.declaredColumn(table, column.columnName());
        if (physical == null) {
            unknownColumn(context, schema, table, column.columnName(), column.attributeDescription());
            return false;
        }
        if (!boundedCharacterType(physical)) {
            return false;
        }
        if (column.ambiguousType()) {
            unknown(
                    context,
                    column.attributeDescription()
                            + ": effective JDBC representation is unknown for length comparison.");
            return false;
        }
        Integer size = physical.size();
        if (size == null || size <= 0 || size == Integer.MAX_VALUE) {
            unknown(context, column.attributeDescription() + ": no positively bounded character-column size is known.");
            return false;
        }
        if (size < declaredLength) {
            details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " declares @Column(length="
                    + declaredLength + "), which is longer than physical column " + table.qualifiedName() + "."
                    + column.columnName() + " (" + physical.describeType() + "). Review this DDL declaration; "
                    + "it is not a runtime length validator.");
        }
        return true;
    }

    private static boolean boundedCharacterType(ColumnModel column) {
        String typeName = column.typeName() == null ? "" : column.typeName().toLowerCase(java.util.Locale.ROOT);
        if (typeName.contains("text") || typeName.contains("clob") || typeName.contains("max")) {
            return false;
        }
        // MySQL/MariaDB report native ENUM/SET as character types sized to the longest label, which Hibernate
        // uses for @Enumerated(STRING) by default; the label list, not a length, bounds those values.
        if (nativeLabelType(typeName, "enum") || nativeLabelType(typeName, "set")) {
            return false;
        }
        return column.jdbcType() == Types.CHAR
                || column.jdbcType() == Types.VARCHAR
                || column.jdbcType() == Types.NCHAR
                || column.jdbcType() == Types.NVARCHAR;
    }

    private static boolean nativeLabelType(String typeName, String keyword) {
        return typeName.equals(keyword) || typeName.startsWith(keyword + "(");
    }
}
