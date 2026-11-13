package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.List;

/**
 * A PostgreSQL declaratively partitioned parent table ({@code pg_class.relkind = 'p'}) that has zero child
 * partitions attached in {@code pg_inherits}. Because a partitioned parent holds no storage of its own, every
 * {@code INSERT} into it fails immediately at runtime with {@code no partition of relation "..." found for row}.
 */
final class PostgresEmptyPartitionedTableRule extends AbstractDatabaseAdvisorRule {

    PostgresEmptyPartitionedTableRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-006",
                "PostgreSQL partitioned table has no attached partitions",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects declaratively partitioned parent tables (pg_class.relkind = 'p') with no child partitions "
                        + "in pg_inherits, excluding system and extension-owned tables.",
                "Create or attach at least one partition (or a DEFAULT partition) for the partitioned table before "
                        + "inserting rows, or drop the unused partitioned parent table.",
                "https://www.postgresql.org/docs/current/ddl-partitioning.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> postgres = context.schemasOf(Dialect.POSTGRESQL);
        List<SchemaSnapshot> schemas = postgres.stream()
                .filter(schema -> !schema.version().known() || schema.version().atLeast(10, 0))
                .toList();
        if (!postgres.isEmpty() && schemas.isEmpty()) {
            return skipped("Not applicable: declarative partitioning requires PostgreSQL 10 or later.");
        }
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.POSTGRES_PARTITIONS,
                "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        DatabaseFindings details = new DatabaseFindings();
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.POSTGRES_PARTITIONS);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_PARTITIONS)) {
                continue;
            }
            for (PostgresPartitionInfo partition :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_PARTITIONS)) {
                if (!partition.partitionedParent() || !partition.emptyPartitionedParent()) {
                    continue;
                }
                details.add(
                        schema.dataSourceName() + ": partitioned table " + partition.qualifiedTable()
                                + " has no attached partitions in pg_inherits; every INSERT into it will fail until a "
                                + "partition is attached.",
                        DatabaseIdentities.table(schema.dataSourceName(), null, partition.schema(), partition.table()));
            }
        }
        int eligible = (int) schemas.stream()
                .filter(schema -> VendorRuleSupport.complete(schema, VendorFindingKinds.POSTGRES_PARTITIONS))
                .count();
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
