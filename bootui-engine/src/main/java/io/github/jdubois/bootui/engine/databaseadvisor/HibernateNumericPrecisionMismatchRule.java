package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.sql.Types;
import java.util.List;
import java.util.Set;

/**
 * Compares a positive {@code @Column(precision, scale)} declaration with a positively bounded physical
 * {@code DECIMAL}/{@code NUMERIC} column. A narrower physical scale silently rounds values on write, and fewer
 * physical integer digits reject (or, in non-strict MySQL/MariaDB modes, clamp) values the declaration allows;
 * Hibernate schema validation compares neither.
 *
 * <p>Only {@code BigDecimal}/{@code BigInteger} attributes without converters, native column definitions or
 * custom types are compared. Unconstrained columns (PostgreSQL {@code numeric} without a type modifier, Oracle
 * {@code NUMBER} without precision), negative scales and a scale larger than the precision are unknown rather
 * than guessed.</p>
 */
final class HibernateNumericPrecisionMismatchRule extends AbstractHibernateCrossReferenceRule {

    private static final Set<String> DECIMAL_JAVA_TYPES = Set.of("BigDecimal", "BigInteger");

    /** pgjdbc and other drivers report sentinel sizes of 1000 or more for an unconstrained numeric. */
    private static final int MAX_DECLARED_PRECISION = 1000;

    HibernateNumericPrecisionMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-010",
                "Declared numeric precision or scale exceeds the observed column",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares a positive @Column(precision=..., scale=...) on a BigDecimal/BigInteger attribute with a "
                        + "bounded physical DECIMAL/NUMERIC column: fewer fractional digits or fewer integer digits "
                        + "than declared. Converters, native column definitions and unconstrained columns are not compared.",
                "Align the declaration and the column. A narrower physical scale silently rounds written values, and "
                        + "fewer physical integer digits reject large values (or clamp them in non-strict MySQL/MariaDB "
                        + "modes). @Column(precision/scale) is schema-generation metadata, not input validation.",
                "https://www.postgresql.org/docs/current/datatype-numeric.html#DATATYPE-NUMERIC-DECIMAL"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.columns().stream().anyMatch(HibernateNumericPrecisionMismatchRule::applicable);
    }

    private static boolean applicable(MappedColumnFacts column) {
        return column.declaredPrecision() != null
                && column.declaredPrecision() > 0
                && column.declaredScale() != null
                && !column.lob()
                && DECIMAL_JAVA_TYPES.contains(column.javaTypeSimpleName());
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
        ColumnModel physical = schema.declaredColumn(table, column.columnName());
        if (physical == null) {
            unknownColumn(context, schema, table, column.columnName(), column.attributeDescription());
            return false;
        }
        if (physical.jdbcType() != Types.DECIMAL && physical.jdbcType() != Types.NUMERIC) {
            return false;
        }
        if (column.ambiguousType()) {
            unknown(
                    context,
                    column.attributeDescription()
                            + ": effective JDBC representation is unknown for precision comparison.");
            return false;
        }
        int precision = column.declaredPrecision();
        int scale = column.declaredScale();
        if (scale < 0 || scale > precision) {
            unknown(context, column.attributeDescription() + ": declared scale is outside the declared precision.");
            return false;
        }
        Integer size = physical.size();
        Integer digits = physical.decimalDigits();
        if (size == null
                || size <= 0
                || size >= MAX_DECLARED_PRECISION
                || digits == null
                || digits < 0
                || digits > size) {
            unknown(
                    context,
                    column.attributeDescription() + ": no bounded physical precision and scale are known for "
                            + table.qualifiedName() + "." + physical.name() + ".");
            return false;
        }
        boolean rounds = digits < scale;
        boolean overflows = size - digits < precision - scale;
        if (rounds || overflows) {
            String consequence = rounds && overflows
                    ? "rounds fractional digits and rejects large values"
                    : rounds
                            ? "rounds written values to " + digits + " fractional digits"
                            : "holds only " + (size - digits) + " integer digits instead of " + (precision - scale);
            details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " declares @Column(precision="
                    + precision + ", scale=" + scale + "), but physical column " + table.qualifiedName() + "."
                    + physical.name() + " (" + physical.describeType() + ") " + consequence
                    + ". Review this DDL declaration; it is not a runtime validator.");
        }
        return true;
    }
}
