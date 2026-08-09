package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: an index whose {@code pg_index.indisvalid} flag is {@code false} is broken and
 * unusable — typically left behind by a failed {@code CREATE INDEX CONCURRENTLY} — yet it still consumes
 * storage and slows down writes without ever being used by the planner.
 */
final class PostgresInvalidIndexRule extends AbstractDatabaseAdvisorRule {

    PostgresInvalidIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-001",
                "Invalid PostgreSQL indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL indexes reported invalid by pg_index.indisvalid, typically left behind by a "
                        + "failed CREATE INDEX CONCURRENTLY.",
                "Drop and recreate the index (DROP INDEX CONCURRENTLY followed by CREATE INDEX CONCURRENTLY). "
                        + "An invalid index is never used by the query planner but still pays the full write cost "
                        + "of index maintenance.",
                "https://www.postgresql.org/docs/current/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        boolean anyPostgres = false;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            if (schema.dialect() != Dialect.POSTGRESQL) {
                continue;
            }
            anyPostgres = true;
            for (PostgresInvalidIndex invalidIndex : schema.postgresInvalidIndexes()) {
                details.add(schema.dataSourceName() + ": index " + invalidIndex.indexName() + " on table "
                        + invalidIndex.tableName() + " is invalid.");
            }
        }
        if (!anyPostgres) {
            return skipped("No PostgreSQL datasource was detected.");
        }
        return violation(details);
    }
}
