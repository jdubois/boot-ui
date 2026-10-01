package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Unlogged tables skip write-ahead logging: PostgreSQL truncates them after a crash or immediate shutdown, they
 * are not replicated to physical standbys (where they cannot be read), and WAL-based point-in-time recovery does
 * not restore their contents. Unlogged storage is always an explicit DDL choice, so this is a LOW durability
 * review prompt rather than a defect; a logical {@code pg_dump} still copies their rows.
 */
final class PostgresUnloggedTableRule extends AbstractDatabaseAdvisorRule {

    PostgresUnloggedTableRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-005",
                "PostgreSQL unlogged tables",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects ordinary tables and leaf partitions with pg_class.relpersistence = 'u', excluding system "
                        + "and extension-owned tables.",
                "Confirm the table's data may be lost: PostgreSQL truncates unlogged tables after a crash, does not "
                        + "replicate them to physical standbys and cannot restore them by WAL-based point-in-time "
                        + "recovery. If durability is required, ALTER TABLE ... SET LOGGED rewrites and WAL-logs the table.",
                "https://www.postgresql.org/docs/current/sql-createtable.html#SQL-CREATETABLE-UNLOGGED"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.POSTGRES_UNLOGGED_TABLES,
                "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.POSTGRES_UNLOGGED_TABLES);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_UNLOGGED_TABLES)) {
                continue;
            }
            for (PostgresUnloggedTable table :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_UNLOGGED_TABLES)) {
                details.add(schema.dataSourceName() + ": " + (table.partition() ? "partition " : "table ")
                        + table.qualifiedTable()
                        + " is UNLOGGED: its rows are truncated after a crash and are absent from physical standbys "
                        + "and WAL-based point-in-time recovery.");
            }
        }
        int eligible = (int) schemas.stream()
                .filter(schema -> VendorRuleSupport.complete(schema, VendorFindingKinds.POSTGRES_UNLOGGED_TABLES))
                .count();
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
