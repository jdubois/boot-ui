package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.sql.Types;
import java.util.Locale;

/**
 * Compares the longest constant name of an explicit {@code @Enumerated(EnumType.STRING)} attribute (without
 * {@code @EnumeratedValue}, converters, or native column definitions) against the bounded length of its
 * physical {@code VARCHAR}/{@code CHAR} column. Unlike {@code DB-HIB-004}, this catches enum constants that
 * exceed a physical column even when {@code @Column(length)} was left at the JPA default of 255.
 */
final class HibernateEnumValueLengthMismatchRule extends AbstractHibernateCrossReferenceRule {

    HibernateEnumValueLengthMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-013",
                "Enum constant name exceeds observed character column length",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Compares the longest constant name of an explicit @Enumerated(EnumType.STRING) attribute against "
                        + "bounded physical VARCHAR/CHAR column lengths. Enums with @EnumeratedValue, converters, "
                        + "native ENUM/SET columns and unbounded text columns are excluded.",
                "Widen the physical character column so every enum constant name fits, or shorten/convert the enum "
                        + "representation. Persisting an enum constant longer than the column fails or truncates at runtime.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.columns().stream().anyMatch(HibernateEnumValueLengthMismatchRule::applicable);
    }

    private static boolean applicable(MappedColumnFacts column) {
        return column.maxEnumConstantLength() != null
                && column.maxEnumConstantLength() > 0
                && column.maxEnumConstantName() != null
                && !column.lob()
                && !column.ambiguousType();
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
            DatabaseFindings details) {
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
            DatabaseFindings details) {
        ColumnModel physical = schema.declaredColumn(table, column.columnName());
        if (physical == null) {
            unknownColumn(context, schema, table, column.columnName(), column.attributeDescription());
            return false;
        }
        if (!boundedCharacterType(physical)) {
            return false;
        }
        Integer size = physical.size();
        if (size == null || size <= 0 || size == Integer.MAX_VALUE) {
            unknown(context, column.attributeDescription() + ": no positively bounded character-column size is known.");
            return false;
        }
        if (size < column.maxEnumConstantLength()) {
            details.add(
                    schema.dataSourceName() + ": " + column.attributeDescription()
                            + " is @Enumerated(STRING) with constant " + column.maxEnumConstantName() + " (length "
                            + column.maxEnumConstantLength() + "), which exceeds physical column "
                            + table.qualifiedName() + "." + column.columnName() + " (" + physical.describeType()
                            + "); persisting that constant will fail or truncate.",
                    DatabaseIdentities.mapped(schema, table, column.attributeDescription(), column.columnName()));
        }
        return true;
    }

    private static boolean boundedCharacterType(ColumnModel column) {
        String typeName = column.typeName() == null ? "" : column.typeName().toLowerCase(Locale.ROOT);
        if (typeName.contains("text") || typeName.contains("clob") || typeName.contains("max")) {
            return false;
        }
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
