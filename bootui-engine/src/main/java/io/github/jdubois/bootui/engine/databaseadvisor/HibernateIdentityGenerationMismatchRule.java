package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;
import java.util.Set;

/**
 * An {@code @Id} declaring {@code @GeneratedValue(strategy = IDENTITY)} whose physical column the driver
 * explicitly reports as neither auto-incremented/identity, defaulted nor generated. Hibernate omits an IDENTITY
 * identifier from the {@code INSERT} and reads back the database-generated key, so such inserts fail (or, on a
 * nullable column or in non-strict MySQL modes, store a placeholder) unless something the metadata cannot show,
 * such as a {@code BEFORE INSERT} trigger, assigns the key. Hibernate schema validation does not compare this.
 *
 * <p>Only PostgreSQL, MySQL and MariaDB are compared: pgjdbc reports {@code IS_AUTOINCREMENT = YES} for identity
 * columns and {@code nextval(...)} defaults, and the MySQL/MariaDB drivers report {@code AUTO_INCREMENT}. Other
 * drivers' {@code IS_AUTOINCREMENT} semantics are not established, and a missing or empty value is unknown,
 * never {@code NO}. {@code GenerationType.AUTO} is provider-selected and is not treated as IDENTITY.</p>
 */
final class HibernateIdentityGenerationMismatchRule extends AbstractHibernateCrossReferenceRule {

    private static final Set<Dialect> VERIFIED_DIALECTS = Set.of(Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.MARIADB);

    HibernateIdentityGenerationMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-009",
                "IDENTITY identifier column without database-side generation",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares an explicitly named @Id with @GeneratedValue(strategy = IDENTITY) against PostgreSQL, "
                        + "MySQL and MariaDB column metadata explicitly reporting IS_AUTOINCREMENT = NO, no COLUMN_DEF "
                        + "default and no generated column. Unknown driver metadata and other dialects are not compared.",
                "Check for a BEFORE INSERT trigger that assigns the key, then either make the column database-generated "
                        + "(GENERATED ... AS IDENTITY or AUTO_INCREMENT) or change the generation strategy. Hibernate "
                        + "omits an IDENTITY key from INSERT and expects the database to generate it.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.columns().stream().anyMatch(HibernateIdentityGenerationMismatchRule::applicable);
    }

    private static boolean applicable(MappedColumnFacts column) {
        return column.identifier() && column.identityGenerated();
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
            if (!resolution.resolved()
                    || !VERIFIED_DIALECTS.contains(resolution.schema().dialect())) {
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
        if (physical.autoIncrementReported() == null) {
            unknown(
                    context,
                    column.attributeDescription() + ": the driver did not report IS_AUTOINCREMENT/COLUMN_DEF for "
                            + table.qualifiedName() + "." + physical.name() + ".");
            return false;
        }
        if (physical.knownWithoutDatabaseGeneration()) {
            details.add(schema.dataSourceName() + ": " + column.attributeDescription()
                    + " declares @GeneratedValue(strategy = IDENTITY), but physical column " + table.qualifiedName()
                    + "." + physical.name() + " (" + physical.describeType() + ") reports no auto-increment/identity, "
                    + "default or generated value. Inserts fail unless a trigger assigns the key.");
        }
        return true;
    }
}
