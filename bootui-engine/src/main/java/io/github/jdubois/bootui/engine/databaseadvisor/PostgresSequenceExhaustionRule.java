package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: a sequence ({@code pg_sequences.last_value}) that has consumed a large fraction of
 * the numeric range of its underlying data type is at risk of running out of values, a well-documented
 * outage cause when an {@code int4}-backed identity/serial column nears ~2.1 billion.
 */
final class PostgresSequenceExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    PostgresSequenceExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-002",
                "PostgreSQL sequence nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL sequences (pg_sequences.last_value) that have consumed at least "
                        + WARNING_PERCENT_USED + "% of the numeric range of their underlying data type.",
                "Convert the sequence's backing column (and the sequence itself) to a wider type (e.g. int4 to "
                        + "int8/bigint), or restart the sequence after archiving/compacting old rows. A sequence "
                        + "that reaches the maximum value of its type causes every subsequent insert relying on it "
                        + "to fail outright.",
                "https://www.postgresql.org/docs/current/view-pg-sequences.html"));
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
            for (PostgresSequenceNearingExhaustion sequence : schema.postgresSequencesNearingExhaustion()) {
                details.add(schema.dataSourceName() + ": sequence " + sequence.sequenceName() + " is at "
                        + sequence.percentUsed() + "% of its type's range (last_value " + sequence.lastValue()
                        + " of max " + sequence.maxValue() + ").");
            }
        }
        if (!anyPostgres) {
            return skipped("No PostgreSQL datasource was detected.");
        }
        return violation(details);
    }
}
